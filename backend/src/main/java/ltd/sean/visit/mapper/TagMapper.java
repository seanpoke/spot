package ltd.sean.visit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ltd.sean.visit.dto.TagUsageRow;
import ltd.sean.visit.entity.Tag;

import java.util.List;

/**
 * 标签字典数据访问。
 *
 * <p>带使用次数的统计查询在 {@code resources/mapper/TagMapper.xml}。
 */
public interface TagMapper extends BaseMapper<Tag> {

    /** 标签库列表（含使用次数），顺序由 SQL 保证：sort_order → name */
    List<TagUsageRow> selectTagUsage();
}
