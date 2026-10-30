package ltd.sean.visit.dto;

import java.util.List;

/**
 * 「删这个标签会影响谁」的预检结果。
 *
 * <p>删除标签是破坏性操作：标签一删，所有挂了它的打卡点都会少一个标签。
 * 前端在弹确认框之前先拿这份数据，把影响面摊开说清楚（几个点、都是谁），
 * 而不是只弹一句「确定删除吗」。
 *
 * @param useCount    被多少个打卡点使用；0 表示闲置，可以放心删
 * @param sampleSpots 受影响的打卡点名（最多 {@code TagService#SAMPLE_LIMIT} 个），仅供展示
 */
public record TagUsage(String id, String name, long useCount, List<String> sampleSpots) {
}
