package ltd.sean.visit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import ltd.sean.visit.common.BizException;
import ltd.sean.visit.dto.TagDeleteResult;
import ltd.sean.visit.dto.TagListItem;
import ltd.sean.visit.dto.TagUsage;
import ltd.sean.visit.entity.SpotTag;
import ltd.sean.visit.entity.Tag;
import ltd.sean.visit.entity.TagCategory;
import ltd.sean.visit.mapper.SpotTagMapper;
import ltd.sean.visit.mapper.TagCategoryMapper;
import ltd.sean.visit.mapper.TagMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 标签字典。
 *
 * <p>核心语义：<b>同名标签自动复用，绝不产生第二行</b>（PRD 5.6）。
 * 新建入口已经从打卡点编辑页挪到「我的 → 编辑标签」（{@code /tags}），
 * 但接口语义没变：同名仍复用，数据库的 {@code uk_tag_name} 是最后一道兜底。
 *
 * <p>删除是<b>两步式</b>：先 {@link #usage(String)} 查影响面，前端据此弹确认框；
 * 用户确认后带 {@code force=true} 再删，删除时在同一事务里先清 spot_tag 再删 tag。
 * 不带 force 且有绑定 → 409，绝不静默连带摘掉十几个打卡点的标签。
 */
@Slf4j
@Service
public class TagService {

    private static final int MAX_NAME_LENGTH = 24;

    /** 确认框里最多列几个受影响的打卡点名；剩下的用「等 N 个点」概括 */
    public static final int SAMPLE_LIMIT = 5;

    private final TagMapper tagMapper;
    private final SpotTagMapper spotTagMapper;
    private final TagCategoryMapper categoryMapper;

    public TagService(TagMapper tagMapper, SpotTagMapper spotTagMapper, TagCategoryMapper categoryMapper) {
        this.tagMapper = tagMapper;
        this.spotTagMapper = spotTagMapper;
        this.categoryMapper = categoryMapper;
    }

    public List<TagListItem> list() {
        return tagMapper.selectTagUsage().stream()
                .map(row -> new TagListItem(
                        row.getId(),
                        row.getName(),
                        row.getColor(),
                        categoryOrDefault(row.getCategory()),
                        row.getSortOrder() == null ? 0 : row.getSortOrder(),
                        row.getUseCount() == null ? 0L : row.getUseCount()))
                .toList();
    }

    /**
     * 新建标签；同名已存在时直接返回已有的那个（不报错，前端无感知）。
     * 复用已有标签时，如果这次带了 color 而旧标签还没颜色，会补写上去。
     *
     * <p>同名复用**不改已有标签的分类**：分类是用户手动归类的成果，
     * 不能因为别处顺手建了个同名标签就被覆盖。
     */
    public TagListItem createOrReuse(String rawName, String rawColor, String rawCategory) {
        String name = normalizeName(rawName);
        String color = normalizeColor(rawColor);
        String category = normalizeCategory(rawCategory);

        Tag existing = findByName(name);
        if (existing != null) {
            if (color != null && existing.getColor() == null) {
                existing.setColor(color);
                tagMapper.updateById(existing);
            }
            return toListItem(existing);
        }

        Tag tag = new Tag();
        tag.setName(name);
        tag.setColor(color);
        tag.setCategory(category);
        tag.setSortOrder(nextSortOrder());
        try {
            tagMapper.insert(tag);
        } catch (DuplicateKeyException e) {
            // 并发下两个请求同时建同名标签：uk_tag_name 挡住后面那个，取回先建成的即可
            Tag raced = findByName(name);
            if (raced == null) {
                throw e;
            }
            log.debug("并发建同名标签，复用已有：{}", name);
            return toListItem(raced);
        }
        return new TagListItem(tag.getId(), tag.getName(), tag.getColor(),
                tag.getCategory(), tag.getSortOrder(), 0L);
    }

    /**
     * 改名 / 改色。
     *
     * <p>改名会连带影响所有挂了这个标签的打卡点 —— 这正是「编辑标签」要集中到
     * 一个页面来的原因：在一处改，全库语义一致。重名直接 409，不让两个标签同名
     * （{@code uk_tag_name} 也会兜，但先查一次能给出更好读的提示）。
     *
     * <p>{@code color} 传 null 表示清空颜色（{@code Tag.color} 标了 ALWAYS 策略，
     * updateById 会把 null 写进去），前端「不选颜色」走的就是这条路。
     */
    public TagListItem update(String id, String rawName, String rawColor, String rawCategory) {
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw BizException.notFound("标签不存在，可能已经被删掉");
        }
        String name = normalizeName(rawName);
        String color = normalizeColor(rawColor);
        String category = normalizeCategory(rawCategory);

        Tag same = findByName(name);
        if (same != null && !same.getId().equals(id)) {
            throw new BizException(409, "已有同名标签「" + name + "」，换个名字吧");
        }

        tag.setName(name);
        tag.setColor(color);
        tag.setCategory(category);
        try {
            tagMapper.updateById(tag);
        } catch (DuplicateKeyException e) {
            throw new BizException(409, "已有同名标签「" + name + "」，换个名字吧");
        }
        return toListItem(tag);
    }

    /** 删标签前的影响面：被几个点用、都是谁（最多 {@value #SAMPLE_LIMIT} 个） */
    public TagUsage usage(String id) {
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw BizException.notFound("标签不存在，可能已经被删掉");
        }
        long used = countBindings(id);
        List<String> samples = used == 0
                ? List.of()
                : spotTagMapper.selectSpotNamesByTagId(id, SAMPLE_LIMIT);
        return new TagUsage(id, tag.getName(), used, samples);
    }

    /**
     * 删除标签（含它所有的绑定关系）。
     *
     * <p>两道闸：
     * <ol>
     *   <li>还有打卡点在用、且没带 {@code force} → 409，提示影响面，让前端弹确认框</li>
     *   <li>带 {@code force} → 同一事务里先删 spot_tag 再删 tag（外键 CASCADE 也能删，
     *       这里显式删是为了让「解除绑定」这件事在代码里可读、返回值能报数）</li>
     * </ol>
     */
    @Transactional
    public TagDeleteResult delete(String id, boolean force) {
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw BizException.notFound("标签不存在，可能已经被删掉");
        }
        long used = countBindings(id);
        if (used > 0 && !force) {
            throw new BizException(409,
                    "标签「" + tag.getName() + "」正被 " + used + " 个打卡点使用，确认后会一并从这些点上去掉");
        }
        if (used > 0) {
            spotTagMapper.delete(new QueryWrapper<SpotTag>().eq("tag_id", id));
        }
        tagMapper.deleteById(id);
        return new TagDeleteResult(used);
    }

    /**
     * 拖拽排序：按传入顺序全量重排 {@code sort_order}，从 1 开始连续。
     *
     * <p>为什么要求全量：只更新被拖动的那几个，没传的会保持旧序号，很容易出现
     * 两个标签同号 —— 同号时靠 name 兜底，用户看到的就是「拖了没反应」。所以宁可
     * 让前端传全量、后端校验数量，对不上就提示刷新重试。
     */
    @Transactional
    public List<TagListItem> sort(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException(400, "排序数据为空");
        }
        Set<String> distinct = new LinkedHashSet<>(ids);
        long total = tagMapper.selectCount(null);
        if (distinct.size() != ids.size() || total != ids.size()) {
            // 排序期间别处新增/删除了标签，前端手上的列表已经过期
            throw new BizException(409, "标签已被别处改动，请刷新后重试");
        }

        int order = 1;
        for (String id : distinct) {
            // 必须用 UpdateWrapper 只 set sort_order：
            // Tag.color 标了 updateStrategy = ALWAYS，走 updateById 会把没填的 color
            // 当成「显式设为 NULL」写进库 —— 排一次序就把所有标签的颜色清空了。
            int updated = tagMapper.update(null, new UpdateWrapper<Tag>()
                    .eq("id", id)
                    .set("sort_order", order));
            if (updated != 1) {
                // 列表里有一个 id 已经不存在了
                throw new BizException(409, "标签已被别处改动，请刷新后重试");
            }
            order++;
        }
        return list();
    }

    private long countBindings(String tagId) {
        Long used = spotTagMapper.selectCount(new QueryWrapper<SpotTag>().eq("tag_id", tagId));
        return used == null ? 0L : used;
    }

    private String normalizeName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new BizException(400, "标签名不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BizException(400, "标签名最多 " + MAX_NAME_LENGTH + " 个字符");
        }
        return name;
    }

    /** 颜色只允许 #RGB / #RRGGBB，其它一律当没选处理（不报错，前端回退默认） */
    private String normalizeColor(String rawColor) {
        if (rawColor == null) {
            return null;
        }
        String color = rawColor.trim().toLowerCase();
        if (color.matches("^#([0-9a-f]{3}|[0-9a-f]{6})$")) {
            return color;
        }
        return null;
    }

    private Tag findByName(String name) {
        return tagMapper.selectOne(new QueryWrapper<Tag>().eq("name", name));
    }

    private TagListItem toListItem(Tag tag) {
        return new TagListItem(tag.getId(), tag.getName(), tag.getColor(),
                categoryOrDefault(tag.getCategory()), tag.getSortOrder(), countBindings(tag.getId()));
    }

    /**
     * 写库时的分类清洗：空 → other，格式不对或不存在的分类 → 400。
     *
     * <p>分类清单在 {@code tag_category} 表里（用户能自己增删），所以要查一次存在性：
     * 不查的话，前端拿着已删分类的旧值提交，标签就会挂到一个永远不会再显示的分类上。
     */
    private String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return TagCategoryService.FALLBACK_CODE;
        }
        String code = raw.trim().toLowerCase();
        if (!code.matches("^[a-z][a-z0-9_]{0,15}$")) {
            throw new BizException(400, "标签分类不合法");
        }
        if (categoryMapper.selectById(code) == null) {
            throw new BizException(400, "标签分类不存在，可能已经被删掉");
        }
        return code;
    }

    /** 读库时的分类兜底：历史行可能是 NULL 或脏值，一律当「其他」，绝不让列表接口因此 500 */
    private String categoryOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return TagCategoryService.FALLBACK_CODE;
        }
        String code = raw.trim().toLowerCase();
        return code.matches("^[a-z][a-z0-9_]{0,15}$") ? code : TagCategoryService.FALLBACK_CODE;
    }

    /** 新标签排到末尾：MAX(sort_order) + 1（空表时是 1），与拖拽排序同一套 1 起的序号 */
    private int nextSortOrder() {
        Object max = tagMapper.selectObjs(
                        new QueryWrapper<Tag>().select("IFNULL(MAX(sort_order), 0)"))
                .stream().findFirst().orElse(null);
        return max == null ? 1 : ((Number) max).intValue() + 1;
    }
}
