package ltd.sean.visit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import ltd.sean.visit.common.BizException;
import ltd.sean.visit.dto.TagListItem;
import ltd.sean.visit.entity.SpotTag;
import ltd.sean.visit.entity.Tag;
import ltd.sean.visit.mapper.SpotTagMapper;
import ltd.sean.visit.mapper.TagMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 标签字典。
 *
 * <p>核心语义：<b>同名标签自动复用，绝不产生第二行</b>（PRD 5.6）。
 * 编辑页里直接敲名字回车就走 {@link #createOrReuse(String)}，
 * 数据库的 {@code uk_tag_name} 是最后一道兜底。
 *
 * <p>删除标签会由外键 CASCADE 连带清掉 spot_tag，打卡点本身不受影响 ——
 * 这正是把标签拆成两张表而不是塞 JSON 数组的好处。
 */
@Slf4j
@Service
public class TagService {

    private static final int MAX_NAME_LENGTH = 24;

    private final TagMapper tagMapper;
    private final SpotTagMapper spotTagMapper;

    public TagService(TagMapper tagMapper, SpotTagMapper spotTagMapper) {
        this.tagMapper = tagMapper;
        this.spotTagMapper = spotTagMapper;
    }

    public List<TagListItem> list() {
        return tagMapper.selectTagUsage().stream()
                .map(row -> new TagListItem(
                        row.getId(),
                        row.getName(),
                        row.getColor(),
                        row.getSortOrder() == null ? 0 : row.getSortOrder(),
                        row.getUseCount() == null ? 0L : row.getUseCount()))
                .toList();
    }

    /**
     * 新建标签；同名已存在时直接返回已有的那个（不报错，前端无感知）。
     * 复用已有标签时，如果这次带了 color 而旧标签还没颜色，会补写上去。
     */
    public TagListItem createOrReuse(String rawName, String rawColor) {
        String name = normalizeName(rawName);
        String color = normalizeColor(rawColor);

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
        return new TagListItem(tag.getId(), tag.getName(), tag.getColor(), tag.getSortOrder(), 0L);
    }

    public void delete(String id) {
        if (tagMapper.selectById(id) == null) {
            throw BizException.notFound("标签不存在");
        }
        // spot_tag 由外键 ON DELETE CASCADE 清掉
        tagMapper.deleteById(id);
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
        Long used = spotTagMapper.selectCount(new QueryWrapper<SpotTag>().eq("tag_id", tag.getId()));
        return new TagListItem(tag.getId(), tag.getName(), tag.getColor(), tag.getSortOrder(), used == null ? 0L : used);
    }

    /** 新标签排到末尾：MAX(sort_order) + 1，删过标签也不会撞号 */
    private int nextSortOrder() {
        Object max = tagMapper.selectObjs(
                        new QueryWrapper<Tag>().select("IFNULL(MAX(sort_order), -1)"))
                .stream().findFirst().orElse(null);
        return max == null ? 0 : ((Number) max).intValue() + 1;
    }
}
