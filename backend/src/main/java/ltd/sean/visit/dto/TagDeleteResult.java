package ltd.sean.visit.dto;

/**
 * 删除标签的结果。
 *
 * @param removedBindings 连带解除的绑定数（0 = 这个标签本来就没被用过）
 */
public record TagDeleteResult(long removedBindings) {
}
