package ltd.sean.visit.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 列表项（地图与列表页共用）。**只放轻量字段**（AGENTS.md 第 12 条）：
 * 不含 description / packages / 联系方式 / photos。
 *
 * @param avatar    已拼好 CDN 前缀的完整 URL，前端直接塞进 &lt;img :src&gt;，不许自己拼
 * @param rankLevel 段位 0~6
 * @param status    unvisited / visited / wishlist / blacklist
 * @param city      城市（可为空）：地图页要「只看定位所在城市」，列表页要按省市区筛
 * @param province  省 / 直辖市（可为空），列表页筛选用
 * @param district  区 / 县（可为空），列表页筛选用
 * @param priceMin  价目里的最低价（null = 没填过价），列表页「价格范围」筛选用
 * @param priceMax  价目里的最高价
 * @param avgRating 平均星级，没有到访记录时为 null（前端此时显示「想打卡 · 距离」）
 * @param tags      已按展示顺序排好；前端按可用宽度实测截断并补 +N
 */
public record SpotListItem(
        String id,
        String kind,
        String name,
        String avatar,
        Integer rankLevel,
        String status,
        BigDecimal lat,
        BigDecimal lng,
        String city,
        String province,
        String district,
        Integer priceMin,
        Integer priceMax,
        BigDecimal avgRating,
        List<TagBrief> tags
) {
}
