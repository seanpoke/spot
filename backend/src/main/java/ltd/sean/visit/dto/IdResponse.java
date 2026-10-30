package ltd.sean.visit.dto;

/**
 * 只需要回一个新建资源 id 时的响应体。不要返回整个实体 —— 前端拿到也用不上。
 */
public record IdResponse(String id) {
}
