package ltd.sean.visit.dto;

/**
 * 一次图片上传的结果，三档都已生成好，前端按用途取。
 *
 * <p>三个地址都是<b>可直接塞进 {@code <img src>} 的完整字符串</b>：
 * 本地开发 `CDN_BASE_URL` 为空 → 返回 `/uploads/2026/10/x_l.jpg` 相对路径（由 Vite proxy 转发到后端）；
 * 线上配了 CDN → 返回 `https://images.sean.ltd/uploads/...`。
 * 前端**不要**自己拼前缀（AGENTS.md 第 17 条）。
 *
 * @param id     文件名里的 UUID，存进 photo 表的 image_id，便于去重与缓存比对
 * @param large  1280 长边，详情页大图
 * @param thumb  400 长边，列表用
 * @param avatar 96×96 方形裁切，头像与地图标记用
 * @param width  large 的实际宽（兜底缩放后的）
 * @param height large 的实际高
 * @param size   large 的字节数
 */
public record UploadedImage(
        String id,
        String large,
        String thumb,
        String avatar,
        int width,
        int height,
        long size
) {
}
