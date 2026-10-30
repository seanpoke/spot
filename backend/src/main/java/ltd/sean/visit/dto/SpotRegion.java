package ltd.sean.visit.dto;

/**
 * 一个「省 / 市 / 区」组合（列表页筛选下拉的选项来源）。
 *
 * <p>以前选项是从「一次性拉回的全量数据」里现取的，改成服务端分页后拿不到全量了，
 * 所以单独出一个去重接口：只返回这些短文本，几十上百行，很轻。
 * 任一段都可能为 null（用户只填了城市没填区，或压根没填地址）。
 *
 * @param province 省 / 直辖市
 * @param city     市
 * @param district 区 / 县
 */
public record SpotRegion(String province, String city, String district) {
}
