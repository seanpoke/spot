package ltd.sean.visit.controller;

import ltd.sean.visit.common.ApiResult;
import ltd.sean.visit.service.HealthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 环境自检接口，实际路径 {@code GET /api/health}（/api 前缀由 WebMvcConfig 统一加）。
 *
 * <p>依赖不通时返回 503 而不是 200 + healthy=false：这样 curl、浏览器、以后可能加的
 * 探活脚本都能只靠状态码判断，不用解析 body。
 *
 * <p>注意：这个路径已在 {@code WebMvcConfig} 的拦截器放行名单里 —— 探活要能在未登录时用。
 */
@Slf4j
@RestController
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResult<HealthService.HealthReport>> health() {
        HealthService.HealthReport report = healthService.check();
        if (report.healthy()) {
            return ResponseEntity.ok(ApiResult.ok(report));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResult.fail(HttpStatus.SERVICE_UNAVAILABLE.value(), "依赖不可用", report));
    }
}
