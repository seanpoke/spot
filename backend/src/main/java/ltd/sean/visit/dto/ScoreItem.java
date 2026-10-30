package ltd.sean.visit.dto;

/**
 * 一项分项评分，原样存进 {@code visit.scores} 这个 JSON 列。
 *
 * <p>后端<b>不关心</b>有几项、各项叫什么（AGENTS.md 第 8 条）——
 * 结构由前端 {@code config/scoreDimensions.ts} 定义，要加一项只改那个文件，不动表、不动 Java。
 *
 * @param key   稳定标识，如 env / service / tech / value
 * @param label 展示名，如「环境」
 * @param value 1~5；null 表示这一项不适用（餐厅没有「技术」可评），算均分时必须跳过
 */
public record ScoreItem(String key, String label, Integer value) {
}
