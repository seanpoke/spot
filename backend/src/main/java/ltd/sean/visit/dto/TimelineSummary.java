package ltd.sean.visit.dto;

import java.math.BigDecimal;

/**
 * 狂人日志顶部的三个数字（PRD 页面清单：日志页要先给一眼总量）。
 *
 * @param total      总到访次数
 * @param monthCount 本月（按服务器本地日期）到访次数
 * @param avgRating  平均星级，保留一位小数；一条评分都没有时为 null
 */
public record TimelineSummary(
        long total,
        long monthCount,
        BigDecimal avgRating
) {
}
