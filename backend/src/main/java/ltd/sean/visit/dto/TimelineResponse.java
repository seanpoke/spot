package ltd.sean.visit.dto;

import java.util.List;

/**
 * 狂人日志的完整响应：三个数字 + 全量到访（最新在前）。
 *
 * <p>不分页是刻意的：这是个人自用应用，到访总量以百为量级，
 * 一次返回能让前端直接按月分组，不必处理「翻页把同一个月切成两段」。
 */
public record TimelineResponse(
        TimelineSummary summary,
        List<TimelineItem> items
) {
}
