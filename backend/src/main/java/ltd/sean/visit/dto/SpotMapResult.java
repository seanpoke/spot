package ltd.sean.visit.dto;

import java.util.List;

/**
 * 地图视野查询结果。
 *
 * <p>{@code truncated} 是这个接口的**必需语义**，不是可选优化：
 * 缩到国家/省级视野时，一个矩形可能罩住几千个点，服务端按 limit 截断。
 * 截断却不告诉前端，表现出来就是「地图上少了点、像丢数据」，
 * 所以前端必须拿它显示「已显示前 N 个，放大看全部」。
 *
 * @param items     视野内的标记，按段位降序 + id 升序（分页稳定）
 * @param truncated 是否因为 limit 截断
 * @param limit     本次生效的条数上限（前端提示文案直接用它，避免两边各写一个数字）
 */
public record SpotMapResult(
        List<SpotMarkerRow> items,
        boolean truncated,
        int limit
) {
}
