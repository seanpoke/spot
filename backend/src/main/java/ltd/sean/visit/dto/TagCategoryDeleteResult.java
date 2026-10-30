package ltd.sean.visit.dto;

/**
 * 删除标签分类的结果。
 *
 * @param code      被删掉的分类 code
 * @param movedTags 原本属于它、被改归到「其他」的标签数
 */
public record TagCategoryDeleteResult(String code, int movedTags) {
}
