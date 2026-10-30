package ltd.sean.visit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用自身参数的集中映射（对应 application.yml 的 {@code app.*}）。
 *
 * <p>全部来自环境变量，源码里不出现任何 IP、域名、口令。变量表见 AGENTS.md 第六节。
 *
 * @param accessPassword 口令门的唯一口令（必填，本地不填则启动后登录必然失败）
 * @param sessionDays    登录保持天数
 * @param baseUrl        外部访问地址，可空：为空时从 X-Forwarded-Host 推导
 * @param cdnBaseUrl     图片 CDN 前缀，<b>本地开发留空</b>以走同域相对路径
 * @param uploadDir      图片落盘目录，默认 ./uploads，线上 /app/uploads
 * @param uploadMaxMb    单文件上限（MB）
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String accessPassword,
        int sessionDays,
        String baseUrl,
        String cdnBaseUrl,
        String uploadDir,
        int uploadMaxMb
) {

    /**
     * 拼接图片可访问地址：数据库只存相对路径（/uploads/2026/10/x.jpg），
     * 绝对地址在返回 DTO 时拼。以 http 开头的值视为手填绝对地址，原样返回。
     *
     * <p><b>线上 URL 里不留 {@code /uploads} 这一段</b>：CDN 前缀已经带了项目名，
     * 再来一层 {@code /uploads} 就是冗余。配合
     * {@code CDN_BASE_URL=https://images.sean.ltd/visit}，最终 URL 是
     * {@code https://images.sean.ltd/visit/2026/10/<uuid>_l.jpg}，
     * 对应磁盘 {@code picture_bed/visit/2026/10/<uuid>_l.jpg}（Nginx {@code alias} 直出）。
     * 本地开发 CDN 前缀为空时仍返回 {@code /uploads/...}，交给 Vite 的 /uploads 代理。
     *
     * <p>这是 AGENTS.md 第 1 条与第 17 条的唯一实现点，前端不得自己拼前缀。
     */
    public String toPublicUrl(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            return null;
        }
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl;
        }

        String path = pathOrUrl.startsWith("/") ? pathOrUrl : "/" + pathOrUrl;
        String prefix = trimSlash(cdnBaseUrl);
        if (prefix.isEmpty()) {
            // 本地开发：保持 /uploads/... 相对路径，由 Vite 的 /uploads 代理转发到后端
            return path;
        }
        return prefix + withoutUploadsSegment(path);
    }

    /**
     * 反向：把前端回传的地址还原成「库里该存的样子」。
     *
     * <p>为什么必须有这一步：编辑页拿到的是 {@link #toPublicUrl} 拼好的完整地址，
     * 用户原样保存时，如果不还原，线上就会把整条 CDN 地址写进库 ——
     * 换 CDN 域名那天全库图片一起挂（AGENTS.md 第 1 条）。
     *
     * <p>两种来源都要认：
     * <ul>
     *   <li>含 {@code /uploads/} 的（本地相对路径、改造前的旧数据）→ 从那里截断；</li>
     *   <li>不含 {@code /uploads/} 的线上地址 → 按当前 CDN 前缀截断并补回 {@code /uploads}。</li>
     * </ul>
     * 都不命中就当作手填外链原样保留（schema 允许）。
     */
    public String toStoredPath(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            return null;
        }
        String value = pathOrUrl.trim();

        int index = value.indexOf("/uploads/");
        if (index > 0) {
            return value.substring(index);
        }

        String prefix = trimSlash(cdnBaseUrl);
        if (!prefix.isEmpty() && value.startsWith(prefix + "/")) {
            return "/uploads" + value.substring(prefix.length());
        }
        return value;
    }

    /** 去掉尾斜杠；null 当空串，避免到处判空 */
    private static String trimSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** {@code /uploads/2026/10/x.jpg} → {@code /2026/10/x.jpg}；没有该段就原样返回 */
    private static String withoutUploadsSegment(String path) {
        return path.startsWith("/uploads/") ? path.substring("/uploads".length()) : path;
    }
}
