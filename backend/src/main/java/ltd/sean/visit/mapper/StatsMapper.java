package ltd.sean.visit.mapper;

import ltd.sean.visit.dto.MonthCountRow;
import ltd.sean.visit.dto.RankCountRow;
import ltd.sean.visit.dto.StatsTotals;
import ltd.sean.visit.dto.TopSpotRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 统计看板的数据访问（只读，没有 BaseMapper）。
 *
 * <p>五条聚合各自独立：把它们硬塞进一条 SQL 会变成一堆标量子查询，
 * 读起来比分开写更难，也拿不到索引优势。SQL 全在
 * {@code resources/mapper/StatsMapper.xml}。
 */
public interface StatsMapper {

    /** 打卡点总数 + 城市数 */
    StatsTotals selectTotals();

    /** 各段位下的打卡点数（只含真实存在的段位） */
    List<RankCountRow> selectRankCounts();

    /** 到访总次数 */
    long selectVisitCount();

    /** 某年各月到访次数（只含有人去过的月份） */
    List<MonthCountRow> selectMonthly(@Param("year") int year);

    /** 去得最多的前 N 个打卡点 */
    List<TopSpotRow> selectTopSpots(@Param("limit") int limit);
}
