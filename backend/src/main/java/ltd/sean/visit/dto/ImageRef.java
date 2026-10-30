package ltd.sean.visit.dto;

/**
 * 一张图片的引用（打卡点照片、到访照片共用）。
 *
 * <p>和大字段 {@code UploadedImage} 的区别：这里只有「展示要用的最小集」，
 * 不含 avatar 派生图。{@code large} / {@code thumb} 在出参里是拼好 CDN 前缀的完整 URL，
 * 在入参里是前端回传的完整 URL（落库前由 Service 还原成 {@code /uploads/...} 相对路径）。
 *
 * @param id     文件名里的 UUID，便于去重与缓存比对
 * @param large  1280 长边，详情页大图
 * @param thumb  400 长边，九宫格缩略图
 * @param width  large 的实际宽
 * @param height large 的实际高
 * @param size   large 的字节数
 */
public record ImageRef(String id, String large, String thumb, int width, int height, long size) {
}
