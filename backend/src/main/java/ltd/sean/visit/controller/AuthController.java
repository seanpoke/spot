package ltd.sean.visit.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import ltd.sean.visit.common.ApiResult;
import ltd.sean.visit.dto.LoginRequest;
import ltd.sean.visit.dto.LoginResponse;
import ltd.sean.visit.dto.SessionInfo;
import ltd.sean.visit.interceptor.AuthInterceptor;
import ltd.sean.visit.service.AuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 口令门：登录 / 退出 / 当前会话。实际路径都在 {@code /api/auth/**} 下。
 *
 * <p>三件事不在这里做：口令比对与会话读写全在 {@link AuthService}，
 * 拦截在 {@code AuthInterceptor}，错误格式在 GlobalExceptionHandler。
 *
 * <p>Cookie 三条约束（AGENTS.md 第 3 条）：HttpOnly、<b>不设 domain</b>、
 * SameSite=Lax；Secure 只在请求本身是 HTTPS 时才加，否则本地 http://localhost
 * 拿不到 Cookie，登录永远成功但页面一直跳回口令门。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResult<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
                                                          HttpServletRequest httpRequest) {
        // 传真实客户端 IP：forward-headers-strategy=framework 已把 X-Forwarded-For 还原到 remoteAddr
        AuthService.Session session = authService.login(request.password(), httpRequest.getRemoteAddr());

        ResponseCookie cookie = cookie(session.token(), session.ttl(), httpRequest.isSecure());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResult.ok(new LoginResponse((int) session.ttl().toDays())));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResult<Void>> logout(
            @CookieValue(name = AuthService.SESSION_COOKIE, required = false) String token,
            HttpServletRequest httpRequest) {
        authService.logout(token);
        // 同名的空 Cookie + Max-Age=0 才能让浏览器删掉它，光删 Redis 会话浏览器还留着旧值
        ResponseCookie cookie = cookie("", Duration.ZERO, httpRequest.isSecure());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResult.ok());
    }

    /**
     * 路由守卫用：能返回 200 就代表会话有效（否则拦截器已经 401 了）。
     */
    @GetMapping("/me")
    public ApiResult<SessionInfo> me(
            @RequestAttribute(AuthInterceptor.SESSION_ATTRIBUTE) AuthService.Session session) {
        return ApiResult.ok(new SessionInfo(true, session.ttl().toSeconds()));
    }

    private ResponseCookie cookie(String value, Duration maxAge, boolean secure) {
        return ResponseCookie.from(AuthService.SESSION_COOKIE, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                // 不设置 domain：默认绑定当前 host，换 IP / 换域名访问都能用
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
