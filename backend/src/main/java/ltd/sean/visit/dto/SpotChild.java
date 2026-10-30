package ltd.sean.visit.dto;

/**
 * 挂在某个场所下的人物（详情页「这个场所有谁」区块用）。
 * 只有跳转与识别所需的最小字段。
 */
public record SpotChild(String id, String name, String avatar, Integer rankLevel) {
}
