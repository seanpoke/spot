package ltd.sean.visit.dto;

/**
 * 列表/详情里内嵌的标签，只有 id、name 与 color。
 * 标签的其它字段（sort_order 等）前端用不到，别顺手带上。
 *
 * @param color 标签颜色 #RRGGBB，可能为空（老标签没选过），前端回退到默认灰底
 */
public record TagBrief(String id, String name, String color) {
}
