package ltd.sean.visit.controller;

import jakarta.validation.Valid;
import ltd.sean.visit.common.ApiResult;
import ltd.sean.visit.dto.IdResponse;
import ltd.sean.visit.dto.TimelineResponse;
import ltd.sean.visit.dto.VisitItem;
import ltd.sean.visit.dto.VisitSaveRequest;
import ltd.sean.visit.service.VisitService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 到访记录接口。
 *
 * <p>路径刻意分两组，而不是统一放在 {@code /visits} 下：
 * <ul>
 *   <li>{@code /spots/{spotId}/visits} —— 归属于某个打卡点，新建时不用在 body 里再传 spotId</li>
 *   <li>{@code /visits/{id}} —— 单条记录的改与删，此时点已经确定了，不需要再带</li>
 * </ul>
 * 没有类级 {@code @RequestMapping}，因为两组前缀不同。
 */
@RestController
public class VisitController {

    private final VisitService visitService;

    public VisitController(VisitService visitService) {
        this.visitService = visitService;
    }

    @GetMapping("/spots/{spotId}/visits")
    public ApiResult<List<VisitItem>> list(@PathVariable String spotId) {
        return ApiResult.ok(visitService.listBySpot(spotId));
    }

    @PostMapping("/spots/{spotId}/visits")
    public ApiResult<IdResponse> create(@PathVariable String spotId,
                                        @Valid @RequestBody VisitSaveRequest request) {
        return ApiResult.ok(new IdResponse(visitService.create(spotId, request)));
    }

    /**
     * 狂人日志。路径是字面量 {@code timeline}，Spring 会优先匹配它而不是 {@code /visits/{id}}。
     */
    @GetMapping("/visits/timeline")
    public ApiResult<TimelineResponse> timeline() {
        return ApiResult.ok(visitService.timeline());
    }

    @GetMapping("/visits/{id}")
    public ApiResult<VisitItem> detail(@PathVariable String id) {
        return ApiResult.ok(visitService.get(id));
    }

    @PutMapping("/visits/{id}")
    public ApiResult<Void> update(@PathVariable String id, @Valid @RequestBody VisitSaveRequest request) {
        visitService.update(id, request);
        return ApiResult.ok();
    }

    @DeleteMapping("/visits/{id}")
    public ApiResult<Void> delete(@PathVariable String id) {
        visitService.delete(id);
        return ApiResult.ok();
    }
}
