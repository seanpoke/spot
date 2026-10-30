package ltd.sean.visit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ltd.sean.visit.dto.SpotTagRow;
import ltd.sean.visit.entity.SpotTag;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 打卡点-标签关系数据访问。
 *
 * <p>列表页取标签必须一次 {@code IN} 查询后按 spot_id 归组（AGENTS.md 第 12 条），
 * SQL 在 {@code resources/mapper/SpotTagMapper.xml}，禁止循环里逐个查。
 */
public interface SpotTagMapper extends BaseMapper<SpotTag> {

    /**
     * 批量取一批打卡点的标签，已按展示顺序排好：
     * {@code spot_tag.sort_order → tag.sort_order → tag.name}（PRD 5.6）。
     */
    List<SpotTagRow> selectTagRowsBySpotIds(@Param("spotIds") Collection<String> spotIds);
}
