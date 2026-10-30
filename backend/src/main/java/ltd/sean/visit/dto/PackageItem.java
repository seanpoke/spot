package ltd.sean.visit.dto;

import java.math.BigDecimal;

/**
 * 价目表的一项，存进 {@code spot.packages} 这个 JSON 列。
 *
 * <p>格式与 db/schema.sql 的注释一致：{@code [{"name":"头部按摩","price":88}]}。
 * price 为 null 表示「有这个项目但价格不写」。
 */
public record PackageItem(String name, BigDecimal price) {
}
