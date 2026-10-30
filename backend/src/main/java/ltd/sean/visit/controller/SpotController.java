package ltd.sean.visit.controller;

import jakarta.validation.Valid;
import ltd.sean.visit.common.ApiResult;
import ltd.sean.visit.dto.IdResponse;
import ltd.sean.visit.dto.SpotDetail;
import ltd.sean.visit.dto.SpotListItem;
import ltd.sean.visit.dto.SpotMapResult;
import ltd.sean.visit.dto.SpotPage;
import ltd.sean.visit.dto.SpotPageQuery;
import ltd.sean.visit.dto.SpotRegion;
import ltd.sean.visit.dto.SpotSaveRequest;
import ltd.sean.visit.service.SpotService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 打卡点接口，实际路径 {@code /api/spots/**}（口令门拦截器已覆盖）。
 *
 * <p>Controller 只做「收参 → 调 Service → 包 ApiResult」，校验规则全在 Service。
 */
@RestController
@RequestMapping("/spots")
public class SpotController {

    private final SpotService spotService;

    public SpotController(SpotService spotService) {
        this.spotService = spotService;
    }

    /**
     * 轻量列表：**地图页抽屉**（按定位所在城市 + 关键字）与编辑页「所属场所」候选在用。
     *
     * <p>地图标记不走这里：标记改成了视野查询（{@link #map}），
     * 这里是「城市内有界的一批点」，前端还要按距离排序、10 条一续。
     *
     * @param status  unvisited / visited / wishlist / blacklist
     * @param kind    place / person
     * @param tagId   只看挂了某个标签的打卡点
     * @param city    只列这个城市的点（「武汉」与「武汉市」都能对上）
     * @param keyword 名称或地址模糊匹配（P1 搜索，先做最简单的）
     */
    @GetMapping
    public ApiResult<List<SpotListItem>> list(@RequestParam(required = false) String status,
                                              @RequestParam(required = false) String kind,
                                              @RequestParam(required = false) String tagId,
                                              @RequestParam(required = false) String city,
                                              @RequestParam(required = false, name = "q") String keyword) {
        return ApiResult.ok(spotService.list(status, kind, tagId, city, keyword));
    }

    /**
     * 地图标记：只返回**当前视野矩形**内的点（bbox，GCJ-02）。
     *
     * <p>为什么不是全量：全表拉回来再让高德裁掉视野外的点，传输、批量查询、标记 DOM 全是白做。
     * 结果里带 {@code truncated}，点太多时前端显示「已显示前 N 个，放大看全部」。
     *
     * @param limit 条数上限，默认 300，最大 500
     */
    @GetMapping("/map")
    public ApiResult<SpotMapResult> map(@RequestParam BigDecimal minLng,
                                        @RequestParam BigDecimal minLat,
                                        @RequestParam BigDecimal maxLng,
                                        @RequestParam BigDecimal maxLat,
                                        @RequestParam(defaultValue = "300") int limit) {
        return ApiResult.ok(spotService.mapMarkers(minLng, minLat, maxLng, maxLat, limit));
    }

    /**
     * 列表页：服务端筛选 + 分页。
     *
     * <p>与 {@link #list} 的区别是「筛完再切页」：所有条件都进 SQL，前端只拿当前页。
     * 多选参数用重复键传（{@code status=visited&status=wishlist}），也接受逗号分隔。
     * 路径比 {@code /{id}} 更长更具体，不会被详情路由吃掉。
     */
    @GetMapping("/page")
    public ApiResult<SpotPage> page(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) List<String> status,
                                    @RequestParam(required = false) List<Integer> rank,
                                    @RequestParam(required = false) List<String> tagId,
                                    @RequestParam(required = false) String province,
                                    @RequestParam(required = false) String city,
                                    @RequestParam(required = false) String district,
                                    @RequestParam(required = false) Integer priceMin,
                                    @RequestParam(required = false) Integer priceMax,
                                    @RequestParam(required = false, name = "q") String keyword) {
        return ApiResult.ok(spotService.page(new SpotPageQuery(page, size, status, rank, tagId,
                province, city, district, priceMin, priceMax, keyword)));
    }

    /** 列表页筛选下拉的省 / 市 / 区选项（去重）；分页后前端拿不到全量，选项必须由服务端给 */
    @GetMapping("/regions")
    public ApiResult<List<SpotRegion>> regions() {
        return ApiResult.ok(spotService.regions());
    }

    @GetMapping("/{id}")
    public ApiResult<SpotDetail> detail(@PathVariable String id) {
        return ApiResult.ok(spotService.detail(id));
    }

    @PostMapping
    public ApiResult<IdResponse> create(@Valid @RequestBody SpotSaveRequest request) {
        return ApiResult.ok(new IdResponse(spotService.create(request)));
    }

    @PutMapping("/{id}")
    public ApiResult<Void> update(@PathVariable String id, @Valid @RequestBody SpotSaveRequest request) {
        spotService.update(id, request);
        return ApiResult.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String id) {
        spotService.delete(id);
        return ApiResult.ok();
    }
}
