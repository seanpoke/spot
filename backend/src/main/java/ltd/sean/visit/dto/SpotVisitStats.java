package ltd.sean.visit.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Mapper 查询结果行：某打卡点的到访统计。
 *
 * <p>列表页只用到 {@code avgRating}（地图标记上的「4.8 · 240m」），
 * 详情页额外用 {@code visitCount} 与 {@code lastVisitAt} 算建议段位。
 * 一次 {@code GROUP BY} 批量取回，禁止在循环里逐点查（N+1）。
 */
@Data
public class SpotVisitStats {

    private String spotId;

    private Long visitCount;

    /** 平均星级，无到访时为 null（不是 0） */
    private BigDecimal avgRating;

    private LocalDate lastVisitAt;
}
