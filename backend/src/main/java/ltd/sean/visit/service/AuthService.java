package ltd.sean.visit.service;

import ltd.sean.visit.common.BizException;
import ltd.sean.visit.common.RedisKeys;
import ltd.sean.visit.config.AppProperties;
import ltd.sean.visit.util.PasswordHasher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 口令门的会话逻辑。
 *
 * <p>设计要点（PRD 7.9 / 风险表）：
 * <ul>
 *   <li><b>只有一个口令</b>，来自环境变量 {@code ACCESS_PASSWORD}，没有账号体系</li>
 *   <li><b>会话存 Redis DB 3</b>，key 带 {@code visit:} 前缀；Redis 不可用时抛异常让
 *       全局处理器返回 503 —— <b>绝不降级放行</b>（AGENTS.md 第 10 条）</li>
 *   <li><b>会话随使用续期</b>（滑动过期，默认 7 天），否则用着用着突然被踢</li>
 *   <li><b>改口令即全端下线</b>：会话值里带「口令版本号」，版本取自口令摘要，
 *       改 {@code .env} 重启后旧 token 全部失效 —— 不用手工清 Redis</li>
 *   <li><b>限流</b>：同一 IP 连续错 5 次锁 10 分钟（与口令门页面上的文案一致）</li>
 * </ul>
 *
 * <p>不改口令重启后端<b>不会</b>把人踢下线：盐每次启动随机，但会话只依赖口令版本号。
 */
@Slf4j
@Service
public class AuthService {

    /** Cookie 名。不要改，改了等于把所有人踢下线 */
    public static final String SESSION_COOKIE = "visit_session";

    /** 连续失败上限，与前端提示文案保持一致 */
    public static final int MAX_FAILURES = 5;
    /** 锁定时长 */
    public static final Duration LOCK_WINDOW = Duration.ofMinutes(10);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Duration DEFAULT_SESSION = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final AppProperties appProperties;

    /** 每次启动随机生成，仅存内存：不需要维护 salt 配置文件 */
    private final byte[] salt = PasswordHasher.randomSalt();
    /** 口令摘要；ACCESS_PASSWORD 未配置时为 null（此时一律拒绝登录） */
    private final byte[] passwordDigest;
    /** 口令版本号：写在每个会话值里，口令一改旧会话立刻作废 */
    private final String passwordVersion;

    public AuthService(StringRedisTemplate redis, AppProperties appProperties) {
        this.redis = redis;
        this.appProperties = appProperties;

        String configured = appProperties.accessPassword();
        if (configured == null || configured.isBlank()) {
            this.passwordDigest = null;
            this.passwordVersion = "";
            log.warn("ACCESS_PASSWORD 未配置：口令门会拒绝所有登录（503）。请填在仓库根目录的 .env 里");
        } else {
            this.passwordDigest = PasswordHasher.derive(configured, salt);
            // 只做版本标识，不做认证依据，所以用无盐 SHA-256 即可
            this.passwordVersion = sha256Hex(configured).substring(0, 12);
        }
    }

    /**
     * 校验口令并签发会话。
     *
     * @throws BizException 429 连续失败过多 / 401 口令不正确 / 503 服务端没配口令
     */
    public Session login(String rawPassword, String clientIp) {
        if (passwordDigest == null) {
            throw BizException.unavailable("服务端未配置访问口令，暂时无法登录");
        }

        String failKey = RedisKeys.GATE_FAIL + clientIp;
        if (failures(failKey) >= MAX_FAILURES) {
            throw new BizException(429, "连续错误次数过多，请 " + LOCK_WINDOW.toMinutes() + " 分钟后再试");
        }

        if (!PasswordHasher.matches(passwordDigest, PasswordHasher.derive(rawPassword, salt))) {
            recordFailure(failKey);
            throw BizException.unauthorized("口令不正确");
        }

        redis.delete(failKey);
        Duration ttl = sessionTtl();
        String token = newToken();
        redis.opsForValue().set(sessionKey(token), passwordVersion + ":" + Instant.now().getEpochSecond(), ttl);
        log.info("口令门通过，签发会话，来源 IP={}", clientIp);
        return new Session(token, ttl);
    }

    /**
     * 校验 token 并续期。返回空表示未登录、会话已过期或口令已更换。
     * 每次请求都命中 Redis，是刻意的：这样「一键失效」立即生效，不需要本地缓存。
     */
    public Optional<Session> touch(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String key = sessionKey(token);
        String stored = redis.opsForValue().get(key);
        if (stored == null) {
            return Optional.empty();
        }
        if (!passwordVersion.equals(versionOf(stored))) {
            // 口令换过了：旧会话立刻作废，顺手删掉省空间
            redis.delete(key);
            return Optional.empty();
        }
        Duration ttl = sessionTtl();
        redis.expire(key, ttl);
        return Optional.of(new Session(token, ttl));
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        redis.delete(sessionKey(token));
    }

    public Duration sessionTtl() {
        int days = appProperties.sessionDays();
        return days > 0 ? Duration.ofDays(days) : DEFAULT_SESSION;
    }

    private String sessionKey(String token) {
        return RedisKeys.SESSION + token;
    }

    /** 会话值是 {@code 版本号:签发时间戳}，只取版本号部分 */
    private String versionOf(String stored) {
        int separator = stored.indexOf(':');
        return separator < 0 ? stored : stored.substring(0, separator);
    }

    private long failures(String key) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void recordFailure(String key) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 只在第一次失败时设过期，后续失败不能把窗口无限延长
            redis.expire(key, LOCK_WINDOW);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 一次会话：token 给 Controller 写 Cookie，ttl 用来算 Max-Age */
    public record Session(String token, Duration ttl) {
    }
}
