package ltd.sean.visit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 编辑标签入参。名称为空或超出长度由校验挡掉，
 * 同名重复不报错 —— 直接复用已有那一行（PRD 5.6：同名标签自动复用）。
 *
 * <p>color 可选：前端新建标签时让用户挑一个颜色，不挑就是空（老标签默认灰底）。
 * 复用已有同名标签时，若这次带了 color 且旧标签没有，会补写上去。
 *
 * <p>category 是分类 code，对应 {@code tag_category.code}（种子为
 * {@code person / place / service / vibe / other}，之后用户可以自己增删）。
 * 后端只校验「格式合法 + 分类存在」，显示名由前端拿分类表换算；
 * 空值一律落成 {@code other}。
 */
public record TagSaveRequest(
        @NotBlank(message = "标签名不能为空")
        @Size(max = 24, message = "标签名最多 24 个字符")
        String name,
        String color,
        String category
) {
}
