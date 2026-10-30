package ltd.sean.visit.dto;

/**
 * 一条联系方式（出入参共用）。
 *
 * <p>保存接口里这个数组**就是全部联系方式**（整体提交、顺序即展示顺序）：
 * 没传或传空数组等于「全清」，不是「不改」。因为编辑页是「加一行 / 删一行」的结构，
 * 局部更新语义会让「删掉最后一个电话」静默失效。
 *
 * @param type  phone=电话，wechat=微信，qq=QQ，telegram=Telegram，youyu=「与你」
 * @param value 电话号码 / 微信号 / QQ号 / 用户名 / ID；空白的行在 Service 里直接丢掉
 */
public record ContactItem(String type, String value) {
}
