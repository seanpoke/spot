package ltd.sean.visit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import ltd.sean.visit.common.BizException;
import ltd.sean.visit.dto.TagCategoryDeleteResult;
import ltd.sean.visit.dto.TagCategoryItem;
import ltd.sean.visit.entity.Tag;
import ltd.sean.visit.entity.TagCategory;
import ltd.sean.visit.mapper.TagCategoryMapper;
import ltd.sean.visit.mapper.TagMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 标签分类（类型）字典。
 *
 * <p>分类以前是写死在前端的枚举，现在改成库里的表：用户要能自己加「身材」「价位」
 * 这类分类，也得能删掉不用的。三条语义：
 * <ol>
 *   <li><b>其他（other）删不掉</b> —— 它是兜底：标签分类为空、或指向已删分类时都按它算</li>
 *   <li><b>删分类不删标签</b> —— 该分类下的标签同一事务里改归「其他」，标签本身与绑定关系不动</li>
 *   <li><b>code 后端生成、label 用户填</b> —— label 只用于显示，code 才是 {@code tag.category} 里存的值</li>
 * </ol>
 */
@Service
public class TagCategoryService {

    /** 兜底分类：不能删，删分类时标签往这里搬 */
    public static final String FALLBACK_CODE = "other";

    private static final int MAX_LABEL_LENGTH = 12;

    private final TagCategoryMapper categoryMapper;
    private final TagMapper tagMapper;

    public TagCategoryService(TagCategoryMapper categoryMapper, TagMapper tagMapper) {
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
    }

    public List<TagCategoryItem> list() {
        return categoryMapper.selectList(new QueryWrapper<TagCategory>()
                        .orderByAsc("sort_order", "label"))
                .stream()
                .map(row -> new TagCategoryItem(
                        row.getCode(),
                        row.getLabel(),
                        row.getSortOrder() == null ? 0 : row.getSortOrder()))
                .toList();
    }

    /** 新分类排在最后（sort_order = MAX + 1），顺序沿用建表时种子分类排出来的先后 */
    @Transactional
    public TagCategoryItem create(String rawLabel) {
        String label = normalizeLabel(rawLabel);
        Long same = categoryMapper.selectCount(new QueryWrapper<TagCategory>().eq("label", label));
        if (same != null && same > 0) {
            throw new BizException(409, "已有同名类型「" + label + "」");
        }

        TagCategory category = new TagCategory();
        category.setCode(nextCode());
        category.setLabel(label);
        category.setSortOrder(nextSortOrder());
        try {
            categoryMapper.insert(category);
        } catch (DuplicateKeyException e) {
            // 并发建同名类型：uk_tag_category_label 挡住后一个
            throw new BizException(409, "已有同名类型「" + label + "」");
        }
        return new TagCategoryItem(category.getCode(), category.getLabel(), category.getSortOrder());
    }

    /**
     * 重命名分类：只改 {@code label}，{@code code} 不动 ——
     * {@code tag.category} 存的是 code，所以改名不会影响任何标签的归属。
     *
     * <p>与别的类型重名返回 409（{@code uk_tag_category_label} 也会兜，先查一次好给提示）。
     */
    @Transactional
    public TagCategoryItem rename(String code, String rawLabel) {
        TagCategory category = categoryMapper.selectById(code);
        if (category == null) {
            throw BizException.notFound("类型不存在，可能已经被删掉");
        }
        String label = normalizeLabel(rawLabel);
        if (!label.equals(category.getLabel())) {
            Long same = categoryMapper.selectCount(new QueryWrapper<TagCategory>().eq("label", label));
            if (same != null && same > 0) {
                throw new BizException(409, "已有同名类型「" + label + "」");
            }
            category.setLabel(label);
            try {
                categoryMapper.updateById(category);
            } catch (DuplicateKeyException e) {
                throw new BizException(409, "已有同名类型「" + label + "」");
            }
        }
        return new TagCategoryItem(category.getCode(), category.getLabel(), category.getSortOrder());
    }

    /**
     * 重排分类顺序：按传入顺序把 {@code sort_order} 重写成 1..n，返回最新列表。
     *
     * <p>必须传全量 code（与标签排序同一套约定）：只传被挪动的那几个会留下同号，
     * 同号时靠 label 兜底，用户看到的就是「点了没反应」。数量对不上说明别处刚增删过，返回 409。
     */
    @Transactional
    public List<TagCategoryItem> sort(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            throw new BizException(400, "排序数据为空");
        }
        Set<String> distinct = new LinkedHashSet<>(codes);
        long total = categoryMapper.selectCount(null);
        if (distinct.size() != codes.size() || total != codes.size()) {
            throw new BizException(409, "类型已被别处改动，请刷新后重试");
        }

        int order = 1;
        for (String code : distinct) {
            // 用 UpdateWrapper 只 set sort_order，别走实体更新把 label 之类的字段一起写一遍
            int updated = categoryMapper.update(null, new UpdateWrapper<TagCategory>()
                    .eq("code", code)
                    .set("sort_order", order));
            if (updated != 1) {
                throw new BizException(409, "类型已被别处改动，请刷新后重试");
            }
            order++;
        }
        return list();
    }

    /**
     * 删除分类：该分类下的标签改归「其他」，标签本身、以及打卡点上的绑定全部保留。
     *
     * <p>「被动消失保留痕迹、主动移除彻底清除」在这里的落法：用户主动删分类，
     * 标签不该跟着消失（那样等于一次删掉一堆标签），把它们降级到兜底分类最安全。
     */
    @Transactional
    public TagCategoryDeleteResult delete(String code) {
        if (FALLBACK_CODE.equals(code)) {
            throw new BizException(400, "「其他」是兜底类型，不能删");
        }
        TagCategory category = categoryMapper.selectById(code);
        if (category == null) {
            throw BizException.notFound("类型不存在，可能已经被删掉");
        }
        // 用 UpdateWrapper 只 set category：Tag.color 标了 ALWAYS，走实体更新会把颜色清空
        int moved = tagMapper.update(null, new UpdateWrapper<Tag>()
                .eq("category", code)
                .set("category", FALLBACK_CODE));
        categoryMapper.deleteById(code);
        return new TagCategoryDeleteResult(code, moved);
    }

    private String normalizeLabel(String rawLabel) {
        String label = rawLabel == null ? "" : rawLabel.trim();
        if (label.isEmpty()) {
            throw new BizException(400, "类型名不能为空");
        }
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new BizException(400, "类型名最多 " + MAX_LABEL_LENGTH + " 个字符");
        }
        return label;
    }

    /**
     * 生成分类 code：固定 {@code c} 前缀 + 7 位十六进制。
     *
     * <p>不用 label 转拼音/中文：{@code tag.category} 的格式约定是小写 ASCII，
     * 而且 label 以后可能改名，code 必须和显示名解耦。
     */
    private String nextCode() {
        for (int i = 0; i < 8; i++) {
            String code = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
            if (categoryMapper.selectById(code) == null) {
                return code;
            }
        }
        throw new BizException(500, "生成类型编码失败，请重试");
    }

    private int nextSortOrder() {
        Object max = categoryMapper.selectObjs(
                        new QueryWrapper<TagCategory>().select("IFNULL(MAX(sort_order), 0)"))
                .stream().findFirst().orElse(null);
        return max == null ? 1 : ((Number) max).intValue() + 1;
    }
}
