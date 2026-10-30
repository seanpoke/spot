package ltd.sean.visit.dto;

import java.math.BigDecimal;

/**
 * 地图标记行：只放画一个标记要用的字段。
 *
 * <p>与 {@link SpotListItem} 的差别就是「不带 tags / 价格 / 省市区」——
 * 地图一次要吞几百个点，标签是每行最贵的那部分（最多 8 个 name+color），
 * 抽屉列表才需要它。字段少一半，视野查询的响应能小一个量级。
 *
 * @param id        打卡点 id
 * @param kind      place / person（标记样式暂时不区分，留着给以后）
 * @param name      标记上的名字
 * @param avatar    已拼好 CDN 前缀的完整 URL；没设头像时用第一张照片兜底，与列表一致
 * @param status    unvisited / visited / wishlist / blacklist，标记外圈颜色
 * @param rankLevel 段位 0~6，标记底部 7 段条
 * @param lat       纬度（GCJ-02，与库中一致，无需转换）
 * @param lng       经度
 * @param avgRating 平均星级（标记第二行文案），没有到访记录时为 null
 */
public record SpotMarkerRow(
        String id,
        String kind,
        String name,
        String avatar,
        String status,
        Integer rankLevel,
        BigDecimal lat,
        BigDecimal lng,
        BigDecimal avgRating
) {
}
