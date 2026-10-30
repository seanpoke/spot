package ltd.sean.visit.controller;

import jakarta.validation.Valid;
import ltd.sean.visit.common.ApiResult;
import ltd.sean.visit.dto.TagCategoryDeleteResult;
import ltd.sean.visit.dto.TagCategoryItem;
import ltd.sean.visit.dto.TagCategorySaveRequest;
import ltd.sean.visit.dto.TagCategorySortRequest;
import ltd.sean.visit.service.TagCategoryService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签分类（类型）接口，实际路径 {@code /api/tag-categories/**}。
 *
 * <p>UI 入口只有一个：「我的 → 编辑标签」（{@code /tags}）里的类型行 ——
 * 点一个类型就弹出【重命名 / 上移 / 下移 / 删除】。
 * 名字与顺序都由 {@code sort_order} + {@code label} 决定，新增的类型排在最后，
 * 想挪位置用 {@code PUT /sort} 全量重排（1..n）。
 */
@RestController
@RequestMapping("/tag-categories")
public class TagCategoryController {

    private final TagCategoryService categoryService;

    public TagCategoryController(TagCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** 全部分类，按 sort_order 升序；编辑页分组顺序与标签管理页的类型排布都用它 */
    @GetMapping
    public ApiResult<List<TagCategoryItem>> list() {
        return ApiResult.ok(categoryService.list());
    }

    /** 新建类型，只收显示名；返回带后端生成的 code */
    @PostMapping
    public ApiResult<TagCategoryItem> create(@Valid @RequestBody TagCategorySaveRequest request) {
        return ApiResult.ok(categoryService.create(request.label()));
    }

    /**
     * 重命名类型：只改显示名，{@code code} 不动，所以不影响任何标签的归属。
     *
     * <p>路径 {@code /tag-categories/sort} 比 {@code /tag-categories/{code}} 更具体，
     * Spring 会优先匹配它，不会被当成 code 为 "sort" 的类型。
     */
    @PutMapping("/sort")
    public ApiResult<List<TagCategoryItem>> sort(@RequestBody TagCategorySortRequest request) {
        return ApiResult.ok(categoryService.sort(request.codes()));
    }

    /** 重命名类型；与别的类型重名返回 409 */
    @PutMapping("/{code}")
    public ApiResult<TagCategoryItem> rename(@PathVariable String code,
                                             @Valid @RequestBody TagCategorySaveRequest request) {
        return ApiResult.ok(categoryService.rename(code, request.label()));
    }

    /** 删除类型：该类型下的标签改归「其他」，标签本身不删 */
    @DeleteMapping("/{code}")
    public ApiResult<TagCategoryDeleteResult> delete(@PathVariable String code) {
        return ApiResult.ok(categoryService.delete(code));
    }
}
