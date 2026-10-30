package ltd.sean.visit.dto;

/**
 * 标签分类出参。
 *
 * @param code      分类 code，写进 {@code tag.category}
 * @param label     显示名，如「人物」
 * @param sortOrder 顺序，1 起连续；前端按它排类型 chip 与编辑页分组
 */
public record TagCategoryItem(String code, String label, int sortOrder) {
}
