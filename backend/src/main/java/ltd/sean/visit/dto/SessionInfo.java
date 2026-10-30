package ltd.sean.visit.dto;

/**
 * 当前会话状态，供前端路由守卫判断「要不要跳口令门」。
 *
 * @param authenticated   走到这个接口说明拦截器已放行，恒为 true
 * @param expiresInSeconds 距过期还有多少秒（每次请求都会续期，所以基本等于会话总长）
 */
public record SessionInfo(boolean authenticated, long expiresInSeconds) {
}
