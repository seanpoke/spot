package ltd.sean.visit.common;

/**
 * Redis key 的唯一出口。
 *
 * <p>AGENTS.md 第 9 条是硬约束：<b>所有 key 必须带 {@code visit:} 前缀，且只用 DB 3</b>。
 * 服务器上的 Redis 是五个项目共用的实例，前缀是唯一的命名隔离手段，
 * 漏前缀的 key 迟早会被别的项目 SCAN 到、误删或被同名覆盖。
 *
 * <p>不要在业务代码里手写字符串拼 key，统一走 {@link #of(String...)}。
 */
public final class RedisKeys {

    /** 全局前缀，不要改 */
    public static final String PREFIX = "visit:";

    /** 口令会话：visit:session:{token} -> 签发时间 */
    public static final String SESSION = PREFIX + "session:";

    /** 口令失败计数（防暴力破解）：visit:gate:fail:{ip} */
    public static final String GATE_FAIL = PREFIX + "gate:fail:";

    private RedisKeys() {
    }

    /** 拼一个带前缀的 key，例如 {@code of("spot", id)} -> {@code visit:spot:xxx} */
    public static String of(String... parts) {
        return PREFIX + String.join(":", parts);
    }
}
