package ltd.sean.visit.dto;

/**
 * 标签库列表项。
 *
 * @param color    标签颜色 #RRGGBB，可能为空
 * @param category 分类 code（person / place / service / vibe / other），编辑页按它分组展示
 * @param useCount 被多少个打卡点使用（0 表示闲置，可考虑清理）
 */
public record TagListItem(String id, String name, String color, String category,
                          Integer sortOrder, long useCount) {
}
