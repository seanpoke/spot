package ltd.sean.visit.dto;

/**
 * 一条业务配置的出参。
 *
 * @param key   配置项标识，如消费项目的 {@code 1P}
 * @param value 配置值，统一是字符串（priceItem 里是默认价格，0 表示不预填）
 * @param desc  说明，如「一次」；只给人看，前端不拿它做判断
 */
public record ConfigItem(String key, String value, String desc) {
}
