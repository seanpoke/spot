package ltd.sean.visit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建标签分类入参。
 *
 * <p>只收 {@code label}（用户敲的名字，如「身材」），{@code code} 由后端生成 ——
 * code 是内部主键，让用户填只会填出空格和中文。
 */
public record TagCategorySaveRequest(
        @NotBlank(message = "类型名不能为空")
        @Size(max = 12, message = "类型名最多 12 个字符")
        String label
) {
}
