package ltd.sean.visit.interceptor;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ltd.sean.visit.common.BizException;
import ltd.sean.visit.service.AuthService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 口令会话校验：拦 {@code /api/**}，未登录一律 401（PRD 任务 #3 的验收标准）。
 *
 * <p>放行名单有三条，缺一条就出 bug：
 * <ol>
 *   <li>{@code /api/auth/login} —— 否则没人能登录</li>
 *   <li>{@code /api/health} —— 探活要能在未登录时用</li>
 *   <li>{@code /uploads/**} —— <b>它根本不在 /api 下，所以不会被拦</b>；这是刻意的，
 *       CDN 回源不带 Cookie，拦了图片就全 401（AGENTS.md 第 15 条）</li>
 * </ol>
 *
 * <p>异常直接抛 {@link BizException}，由 GlobalExceptionHandler 统一转成
 * {@code {code:401,msg:"..."}} 的 JSON —— 不在拦截器里手写响应体，避免两套错误格式并存。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 校验通过的会话挂在请求上，Controller 用 {@code @RequestAttribute} 取，避免二次查 Redis */
    public static final String SESSION_ATTRIBUTE = "visit.session";

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 浏览器预检请求不带 Cookie，拦了会让所有跨域请求直接失败
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        AuthService.Session session = authService.touch(readToken(request))
                .orElseThrow(() -> BizException.unauthorized("请先输入口令"));
        request.setAttribute(SESSION_ATTRIBUTE, session);
        return true;
    }

    private String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthService.SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
