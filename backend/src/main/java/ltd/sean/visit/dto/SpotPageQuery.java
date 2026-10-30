package ltd.sean.visit.dto;

import java.util.List;

/**
 * 列表页分页查询条件。
 *
 * <p>这些条件以前是前端拿到全量后<b>本地筛</b>的，现在全部下沉到 SQL：
 * 分页的前提是「筛完再切页」，否则第 2 页会把第 1 页筛掉的行捡回来。
 *
 * <p>多选条件（状态 / 段位 / 标签）语义：状态与段位是「任一命中」，
 * 标签也是「任一命中」（全选标签会几乎筛空，见列表页原注释）；不同条件之间是 AND。
 *
 * @param page       页码，1 起；越界值由 Service 夹到合法范围
 * @param size       每页条数，默认 20，上限见 SpotService.MAX_PAGE_SIZE
 * @param statuses   unvisited / visited / wishlist / blacklist 的任意组合
 * @param rankLevels 段位 0~6 的任意组合
 * @param tagIds     标签 id 的任意组合
 * @param province   省 / 直辖市（精确匹配）
 * @param city       市（精确匹配）
 * @param district   区 / 县（精确匹配）
 * @param priceMin   价格下限（元）：点位价位跨度的 <b>最高价</b> 要 ≥ 它，等价于「区间有重叠」
 * @param priceMax   价格上限（元）：点位价位跨度的 <b>最低价</b> 要 ≤ 它
 * @param keyword    名称或地址模糊匹配（地址不在列表出参里，只能服务端查）
 */
public record SpotPageQuery(
        int page,
        int size,
        List<String> statuses,
        List<Integer> rankLevels,
        List<String> tagIds,
        String province,
        String city,
        String district,
        Integer priceMin,
        Integer priceMax,
        String keyword
) {
}
