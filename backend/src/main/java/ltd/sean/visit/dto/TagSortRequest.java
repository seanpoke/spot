package ltd.sean.visit.dto;

import java.util.List;

/**
 * 标签拖拽排序入参：按新顺序排好的全部标签 id。
 *
 * <p>要求**全量**：只传一部分会让没传的那些和别人的序号撞上，
 * 结果是一次拖拽把几个标签的优先级改成同一个值。后端会校验数量与库内总数一致。
 */
public record TagSortRequest(List<String> ids) {
}
