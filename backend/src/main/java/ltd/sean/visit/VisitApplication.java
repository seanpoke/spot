package ltd.sean.visit;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 应用入口。
 *
 * <p>三件事在这里定一次，其余地方不再重复：
 * <ul>
 *   <li>{@code @MapperScan} —— Mapper 接口不需要逐个写 {@code @Mapper}</li>
 *   <li>{@code @ConfigurationPropertiesScan} —— 让 {@code config/AppProperties} 这类
 *       配置类自动注册，省掉 {@code @EnableConfigurationProperties}</li>
 *   <li>时区 —— JVM 默认时区固定 Asia/Shanghai，日志与数据库时间才对得上</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("ltd.sean.visit.mapper")
public class VisitApplication {

    public static void main(String[] args) {
        // 容器里已用 -Duser.timezone 指定，本地启动这一行兜底
        System.setProperty("user.timezone", "Asia/Shanghai");
        SpringApplication.run(VisitApplication.class, args);
    }
}
