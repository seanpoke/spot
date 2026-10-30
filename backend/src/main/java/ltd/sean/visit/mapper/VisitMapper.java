package ltd.sean.visit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ltd.sean.visit.dto.SpotVisitStats;
import ltd.sean.visit.entity.Visit;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 到访记录数据访问。
 *
 * <p>统计类查询（平均值、按月、按年）都在 XML 里，不要写进 Service 里拼 SQL。
 */
public interface VisitMapper extends BaseMapper<Visit> {

    /**
     * 批量取一批打卡点的到访统计（次数 / 平均星级 / 最近日期）。
     * 一次 GROUP BY 取回，列表页不再逐点查 —— 这是 N+1 的典型位置。
     */
    List<SpotVisitStats> selectStatsBySpotIds(@Param("spotIds") Collection<String> spotIds);
}
