package ltd.sean.visit.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 口令摘要：JDK 自带 PBKDF2-HMAC-SHA256，零第三方依赖。
 *
 * <p>为什么不用 BCrypt：BCrypt 要引 {@code spring-security-crypto}，而 AGENTS.md
 * 第九条明确禁止引入 Spring Security；「依赖越少越好」这条原则优先于「算法更流行」。
 * PBKDF2 是 NIST 推荐的口令派生算法，对本项目的单口令门完全够用。
 *
 * <p>本项目不存口令哈希（口令本身就是环境变量），这里的派生 + 定时比较做两件事：
 * <ol>
 *   <li>不在内存里长期持有明文口令的 String</li>
 *   <li>比对走 {@link MessageDigest#isEqual}，天然是定时安全比较，避免提前返回泄露信息</li>
 * </ol>
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    /** 迭代次数：本地实测约 60~100ms，纯手工输入的口令门感觉不到 */
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /** 生成盐。本项目每次启动随机生成、只存在内存里，不落盘也不进配置 */
    public static byte[] randomSalt() {
        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        return salt;
    }

    public static byte[] derive(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            // 算法是 JDK 必备项，缺失说明运行环境异常，属于启动期就该暴露的问题
            throw new IllegalStateException("PBKDF2 不可用，检查 JDK 环境", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** 定时安全比较，不要改成 Arrays.equals */
    public static boolean matches(byte[] expected, byte[] actual) {
        return MessageDigest.isEqual(expected, actual);
    }
}
