package ltd.sean.visit.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 统计页（`/stats`）的完整响应：三组总量 + 段位分布 + 每月到访 + 去得最多。
 *
 * <p>一次请求给全：这些数字都来自同几张表，分三个接口只会让页面出现三段加载态。
 *
 * <p>{@code ranks} 固定 7 条、{@code monthly} 固定 12 条，缺失的补 0 ——
 * 补齐在服务端做一次，前端就不用为「这个段位没人」写特例。
 *
 * @param year 每月到访统计的年份；不传就取服务器当前年
 */
public record StatsResponse(
        long spotCount,
        long visitCount,
        long cityCount,
        int year,
        List<RankBucket> ranks,
        List<MonthBucket> monthly,
        List<TopSpotItem> topSpots
) {

    /**
     * 一个段位下的打卡点数。
     *
     * @param level 0~6，与 {@code spot.rank_level} 一致
     */
    public record RankBucket(int level, long count) {
    }

    /**
     * 一个月的到访次数。
     *
     * @param month 1~12
     */
    public record MonthBucket(int month, long count) {
    }

    /**
     * 去得最多的打卡点。
     *
     * @param avatar 已拼好 CDN 前缀的完整 URL，前端直接塞 &lt;img :src&gt;
     */
    public record TopSpotItem(
            String id,
            String name,
            String avatar,
            long visitCount,
            LocalDate lastVisitAt
    ) {
    }
}
