package ltd.sean.visit.dto;

import lombok.Data;

/**
 * Mapper 查询结果行：某年某个月的到访次数（每月到访柱状图用）。
 *
 * <p>没有到访的月份不会出现在结果里，Service 补齐 1~12。
 */
@Data
public class MonthCountRow {

    /** 1~12 */
    private Integer monthNo;

    private Long visitCount;
}
