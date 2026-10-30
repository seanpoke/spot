package ltd.sean.visit.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 环境自检：确认 MySQL 与 Redis 两条连接真的通了。
 *
 * <p>这不是给用户看的功能，是脚手架阶段的验收手段 —— 新机器上第一次启动时，
 * 先访问 {@code GET /api/health} 判断问题在配置还是代码，比翻启动日志快。
 *
 * <p>顺带把 {@code SELECT DATABASE()} 的结果带出来：这台 MySQL 是共享实例
 * （上面还有别的项目库），连错库是真实发生过的坑，看一眼库名最省事。
 */
@Slf4j
@Service
public class HealthService {

    private static final String UP = "up";
    private static final String DOWN = "down";

    private final JdbcTemplate jdbcTemplate;
    private final RedisConnectionFactory redisConnectionFactory;

    public HealthService(JdbcTemplate jdbcTemplate, RedisConnectionFactory redisConnectionFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    public HealthReport check() {
        String database = null;
        String mysql;
        try {
            // 同时验证「连得上」和「连的是哪个库」
            database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
            mysql = UP;
        } catch (DataAccessException e) {
            log.error("MySQL 不可用：{}", e.getMostSpecificCause().getMessage());
            mysql = DOWN;
        }

        String redis;
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            redis = "PONG".equalsIgnoreCase(connection.ping()) ? UP : DOWN;
        } catch (Exception e) {
            log.error("Redis 不可用：{}", e.getMessage());
            redis = DOWN;
        }

        return new HealthReport(UP.equals(mysql) && UP.equals(redis), mysql, redis, database);
    }

    /**
     * @param healthy  两条连接都通才为 true
     * @param mysql    up / down
     * @param redis    up / down
     * @param database 当前连接的库名，连不上时为 null（正常情况下应是 visit_db）
     */
    public record HealthReport(boolean healthy, String mysql, String redis, String database) {
    }
}
