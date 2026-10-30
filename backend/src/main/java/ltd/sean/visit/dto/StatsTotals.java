package ltd.sean.visit.dto;

import lombok.Data;

/**
 * Mapper 查询结果行：统计页顶部两个「点」的数字。
 *
 * <p>城市用 {@code COUNT(DISTINCT city)}：没填城市的点不算一个城市，
 * 否则「城市数」会虚高成「打卡点数」。
 */
@Data
public class StatsTotals {

    private Long spotCount;

    private Long cityCount;
}
