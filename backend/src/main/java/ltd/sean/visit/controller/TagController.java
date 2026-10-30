package ltd.sean.visit.controller;

import jakarta.validation.Valid;
import ltd.sean.visit.common.ApiResult;
import ltd.sean.visit.dto.TagDeleteResult;
import ltd.sean.visit.dto.TagListItem;
import ltd.sean.visit.dto.TagSaveRequest;
import ltd.sean.visit.dto.TagSortRequest;
import ltd.sean.visit.dto.TagUsage;
import ltd.sean.visit.service.TagService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签字典接口，实际路径 {@code /api/tags/**}。
 *
 * <p>UI 上的入口只有一个：<b>我的 → 编辑标签</b>（{@code /tags}）。
 * 打卡点编辑页只做勾选，不再新建 —— 标签是全库共享的字典，
 * 在某个打卡点上顺手新建，很容易攒出一堆同义标签（「按摩」「按摩店」「推拿」）。
 *
 * <p>删除是两步式：先 {@code GET /tags/{id}/usage} 看影响面，
 * 用户确认后再 {@code DELETE /tags/{id}?force=true}。不带 force 且有绑定会 409。
 */
@RestController
@RequestMapping("/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    /** 标签库：带使用次数，供打卡点编辑页勾选与标签管理页展示 */
    @GetMapping
    public ApiResult<List<TagListItem>> list() {
        return ApiResult.ok(tagService.list());
    }

    /** 新建（同名自动复用），返回最终的标签对象 */
    @PostMapping
    public ApiResult<TagListItem> create(@Valid @RequestBody TagSaveRequest request) {
        return ApiResult.ok(tagService.createOrReuse(request.name(), request.color(), request.category()));
    }

    /**
     * 拖拽排序：按传入的完整顺序把 {@code sort_order} 重排成 1..n。
     *
     * <p>路径 {@code /tags/sort} 比 {@code /tags/{id}} 更具体，Spring 会优先匹配它，
     * 不会被当成 id 为 "sort" 的标签。
     */
    @PutMapping("/sort")
    public ApiResult<List<TagListItem>> sort(@RequestBody TagSortRequest request) {
        return ApiResult.ok(tagService.sort(request.ids()));
    }

    /** 改名 / 改色；与别的标签重名返回 409 */
    @PutMapping("/{id}")
    public ApiResult<TagListItem> update(@PathVariable String id, @Valid @RequestBody TagSaveRequest request) {
        return ApiResult.ok(tagService.update(id, request.name(), request.color(), request.category()));
    }

    /** 删除前的影响面预检：被几个打卡点用、都是谁 */
    @GetMapping("/{id}/usage")
    public ApiResult<TagUsage> usage(@PathVariable String id) {
        return ApiResult.ok(tagService.usage(id));
    }

    /**
     * 删除标签，连同它在所有打卡点上的绑定关系。
     *
     * @param force 还有打卡点在用时必须显式传 true（前端在用户点了确认框之后才带），
     *              否则返回 409 与影响面提示 —— 防止误删把十几个点的标签一起摘掉
     */
    @DeleteMapping("/{id}")
    public ApiResult<TagDeleteResult> delete(@PathVariable String id,
                                             @RequestParam(defaultValue = "false") boolean force) {
        return ApiResult.ok(tagService.delete(id, force));
    }
}
