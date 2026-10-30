package ltd.sean.visit.common;

/**
 * 统一响应体：所有接口都返回 {@code {code, msg, data}}，前端只认这一种形状。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code code = 0} 表示成功</li>
 *   <li>失败时 {@code code} 与 HTTP 状态码保持一致（400 / 401 / 404 / 503 / 500），
 *       这样前端既能看 HTTP 状态，也能直接读 body</li>
 *   <li>{@code data} 允许为 null，Jackson 已配置 {@code non_null}，null 字段不会出现在 JSON 里</li>
 * </ul>
 *
 * <p>禁止直接把 Entity 塞进 data —— 走 DTO，见 AGENTS.md 第四节。
 */
public record ApiResult<T>(int code, String msg, T data) {

    private static final int OK_CODE = 0;
    private static final String OK_MSG = "ok";

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(OK_CODE, OK_MSG, data);
    }

    public static ApiResult<Void> ok() {
        return new ApiResult<>(OK_CODE, OK_MSG, null);
    }

    public static <T> ApiResult<T> fail(int code, String msg) {
        return new ApiResult<>(code, msg, null);
    }

    public static <T> ApiResult<T> fail(int code, String msg, T data) {
        return new ApiResult<>(code, msg, data);
    }
}
