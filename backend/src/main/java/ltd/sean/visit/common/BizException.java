package ltd.sean.visit.common;

import lombok.Getter;

/**
 * 可预期的业务异常。Service 层抛它，{@link GlobalExceptionHandler} 统一转成响应。
 *
 * <p>{@code code} 同时作为 HTTP 状态码使用，所以只能填 4xx / 5xx 里的值。
 * 参数校验之类的失败信息要能直接给用户看，不要写成「系统错误」。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(400, message);
    }

    public static BizException notFound(String message) {
        return new BizException(404, message);
    }

    public static BizException unauthorized(String message) {
        return new BizException(401, message);
    }

    /**
     * Redis 等外部依赖不可用时用它。按 AGENTS.md 第 10 条：绝不静默放行，
     * 明确返回 503，让「依赖挂了」和「没登录」在日志里能一眼分开。
     */
    public static BizException unavailable(String message) {
        return new BizException(503, message);
    }
}
