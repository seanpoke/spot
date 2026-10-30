package ltd.sean.visit.config;

import lombok.extern.slf4j.Slf4j;
import ltd.sean.visit.interceptor.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.method.HandlerTypePredicate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Web 层基础配置：/api 前缀 + /uploads 静态映射。
 *
 * <p><b>为什么不用 {@code server.servlet.context-path: /api}</b>：
 * context-path 会把整个应用（包括静态资源）都挪到 /api 下面，图片就变成
 * {@code /api/uploads/x.jpg}，与数据库里存的相对路径 {@code /uploads/x.jpg}、
 * 以及 Vite 的 {@code /uploads} 代理全都对不上。这里改成给所有
 * {@code @RestController} 自动加 /api 前缀，静态资源留在根路径，两条链路都正确。
 *
 * <p>{@code /uploads/**} 的两条硬约束（别「优化」掉）：
 * <ul>
 *   <li>必须带长缓存头 —— 文件名带 UUID 永不变化</li>
 *   <li><b>绝不能加口令校验</b> —— CDN 回源不带 Cookie，加了就全站 401</li>
 * </ul>
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 前端统一入口前缀，与 vite.config.ts 里的代理规则保持一致 */
    public static final String API_PREFIX = "/api";

    private final AppProperties appProperties;
    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(AppProperties appProperties, AuthInterceptor authInterceptor) {
        this.appProperties = appProperties;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_PREFIX, HandlerTypePredicate.forAnnotation(RestController.class));
    }

    /**
     * 口令门拦截：只拦 {@code /api/**}。
     *
     * <p>放行两项：{@code /api/auth/login}（不然没人能登录）、{@code /api/health}（探活）。
     * {@code /uploads/**} 不在 /api 下，因此天然不拦 —— 这是要求，不是巧合。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(API_PREFIX + "/**")
                .excludePathPatterns(
                        API_PREFIX + "/auth/login",
                        API_PREFIX + "/health");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = uploadRoot();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // 目录建不出来也不要让应用起不来：上传功能会自己报错，静态映射先挂个空目录
            log.warn("创建上传目录失败：{}", dir, e);
        }

        // 打出来是因为 upload-dir 是相对路径时按「启动目录」解析：
        // 在 backend/ 下启动是 backend/uploads，在仓库根启动是 ./uploads，
        // 图片 404 时先看这一行，别急着改代码
        log.info("静态图片映射 /uploads/** -> {}", dir);

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(dir.toUri().toString())
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
    }

    /** 上传目录的绝对路径，避免相对路径在不同工作目录下指向不同地方 */
    private Path uploadRoot() {
        String configured = appProperties.uploadDir();
        String dir = (configured == null || configured.isBlank()) ? "./uploads" : configured;
        return Paths.get(dir).toAbsolutePath().normalize();
    }
}
