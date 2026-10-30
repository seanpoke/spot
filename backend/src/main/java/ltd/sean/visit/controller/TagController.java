package ltd.sean.visit.controller;

import jakarta.validation.Valid;
import ltd.sean.visit.common.ApiResult;
import ltd.sean.visit.dto.TagListItem;
import ltd.sean.visit.dto.TagSaveRequest;
import ltd.sean.visit.service.TagService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签字典接口，实际路径 {@code /api/tags/**}。
 *
 * <p>没有 PUT（改名）：标签改名会让历史记录的含义漂移，本项目量级下
 * 「删掉重建」更干净，先不做。真需要时再补，别提前抽象。
 */
@RestController
@RequestMapping("/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    /** 标签库：带使用次数，供编辑页候选与「闲置标签」清理 */
    @GetMapping
    public ApiResult<List<TagListItem>> list() {
        return ApiResult.ok(tagService.list());
    }

    /** 新建（同名自动复用），返回最终的标签对象 */
    @PostMapping
    public ApiResult<TagListItem> create(@Valid @RequestBody TagSaveRequest request) {
        return ApiResult.ok(tagService.createOrReuse(request.name(), request.color()));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String id) {
        tagService.delete(id);
        return ApiResult.ok();
    }
}
