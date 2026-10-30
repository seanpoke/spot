package ltd.sean.visit.dto;

/**
 * 一条「可能重复」的联系方式：我填的这条，和别人家的同名类型撞了。
 *
 * <p>只做**同类型**比较 —— 微信和微信比、电话和电话比。跨类型比较没有意义
 * （微信号是一串字母，电话是数字，撞上了也不是同一个人）。
 *
 * @param type          联系方式类型（phone / wechat / qq / telegram / youyu）
 * @param value         我表单里的值（原样，展示用）
 * @param otherSpotId   撞上的那个打卡点 id（前端「跳转」用）
 * @param otherSpotName 撞上的打卡点名（前端展示成蓝色的可点名字）
 */
public record ContactDuplicate(String type, String value, String otherSpotId, String otherSpotName) {
}
