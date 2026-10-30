package ltd.sean.visit.dto;

import java.util.List;

/**
 * 类型排序入参：按新顺序排好的全部类型 code。
 *
 * <p>与标签排序同一套约定 —— 要求**全量**，只传一部分会留下同号（同号时靠 label
 * 兜底，用户看到的就是「点了没反应」）。后端校验数量与库内总数一致，对不上返回 409。
 */
public record TagCategorySortRequest(List<String> codes) {
}
