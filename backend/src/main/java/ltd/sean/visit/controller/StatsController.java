package ltd.sean.visit.controller;

import ltd.sean.visit.common.ApiResult;
import ltd.sean.visit.dto.StatsResponse;
import ltd.sean.visit.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计看板。只读，实际路径 {@code /api/stats}（要口令）。
 */
@RestController
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * @param year 每月到访的年份；不传取服务器当前年（前端目前也只在页头展示这一年）
     */
    @GetMapping("/stats")
    public ApiResult<StatsResponse> stats(@RequestParam(required = false) Integer year) {
        return ApiResult.ok(statsService.overview(year));
    }
}
