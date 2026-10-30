package ltd.sean.visit.dto;

/**
 * 登录成功的响应。<b>不回传 token</b> —— 它只走 HttpOnly Cookie，JavaScript 读不到，
 * 前端也就没有把它存进 localStorage 的机会。
 *
 * @param expiresInDays 会话保持天数，前端只用来做提示文案
 */
public record LoginResponse(int expiresInDays) {
}
