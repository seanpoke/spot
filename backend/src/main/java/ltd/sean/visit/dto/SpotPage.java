package ltd.sean.visit.dto;

import java.util.List;

/**
 * 打卡点分页结果（列表页专用）。
 *
 * <p>地图页仍然走 {@code GET /api/spots}（一次拉全量轻量字段按视野过滤），
 * 列表页因为条件多、条目多，改成服务端筛选 + 分页，出参就是这一层包装。
 *
 * @param items   当前页的条目，字段与 {@link SpotListItem} 完全一致
 * @param total   命中条件的总条数（不是当前页条数），前端用它显示「已显示 x / 共 y」
 * @param page    当前页码，1 起
 * @param size    每页条数
 * @param hasMore 后面还有没有下一页：翻页期间新增/删除会让 {@code page * size < total} 不可靠，
 *                所以由服务端算好给前端，别让前端自己推
 */
public record SpotPage(
        List<SpotListItem> items,
        long total,
        int page,
        int size,
        boolean hasMore
) {
}
