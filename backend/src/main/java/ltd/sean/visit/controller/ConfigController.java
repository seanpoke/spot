package ltd.sean.visit.controller;

import ltd.sean.visit.common.ApiResult;
import ltd.sean.visit.dto.ConfigItem;
import ltd.sean.visit.service.BaseConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 基础配置（业务字典）接口，实际路径 {@code /api/configs}。
 *
 * <p>只提供读：{@code GET /api/configs?code=priceItem} 返回该业务的一组配置，
 * 顺序即自增 id 顺序。配置本身由 SQL 维护，不在界面上增删改。
 */
@RestController
@RequestMapping("/configs")
public class ConfigController {

    private final BaseConfigService configService;

    public ConfigController(BaseConfigService configService) {
        this.configService = configService;
    }

    /** 按业务编码取一组配置；code 不合法返回 400，code 没有任何行时返回空数组 */
    @GetMapping
    public ApiResult<List<ConfigItem>> list(@RequestParam String code) {
        return ApiResult.ok(configService.listByCode(code));
    }
}
