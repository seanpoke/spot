package ltd.sean.visit.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 狂人日志的一行：一次到访 + 它所属打卡点的最少信息。
 *
 * <p>刻意<b>不带标签</b>：日志展示的是「这一次到访」，不是「这个点」（PRD 5.2 标签规则）。
 *
 * <p>也<b>不带照片</b>：照片统一在 photo 表（owner_type='visit'），只在查到访详情时返回，
 * 时间线列表不取（带宽小）。
 *
 * @param spotName   所属打卡点名；打卡点被删时到访会级联删掉，理论上不会为 null
 * @param spotAvatar 已拼好 CDN 前缀的完整 URL，前端直接塞 &lt;img :src&gt;，不许自己拼
 */
public record TimelineItem(
        String id,
        String spotId,
        String spotName,
        String spotAvatar,
        LocalDate visitedAt,
        Integer rating,
        BigDecimal cost,
        String companions,
        String note
) {
}
