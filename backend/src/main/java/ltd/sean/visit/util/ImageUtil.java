package ltd.sean.visit.util;

import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 图片落盘与派生（PRD 第六章）。
 *
 * <p><b>压缩只做一次</b>：前端 canvas 输出的就是最终 large（1280 / q0.85），
 * 这里正常路径<b>原样落盘、绝不重压</b>，否则两次有损压缩叠加掉画质。
 * 只有「尺寸超标」或「体积超标」才说明前端没压成功，这时才兜底重压。
 *
 * <p>thumb(400) 与 avatar(96 方形裁切) 固定由后端派生：规格统一，且不让前端为主线程再压两遍。
 *
 * <p>产物一律 JPEG（PRD 6.1：WebP 需要原生库，违背「依赖最少」）。PNG 输入会先铺白底再转，
 * 否则透明区域在 JPEG 里会变成黑块。
 */
public final class ImageUtil {

    /** large 最长边：前端已按此压缩，后端只在超标时兜底 */
    public static final int LARGE_EDGE = 1280;
    public static final int THUMB_EDGE = 400;
    public static final int AVATAR_SIZE = 96;

    private static final float LARGE_QUALITY = 0.85f;
    private static final float THUMB_QUALITY = 0.80f;
    private static final float AVATAR_QUALITY = 0.85f;

    /** 超过这个体积就认为前端没压成功（正常压过的图 ~250KB） */
    private static final long PRE_COMPRESSED_BYTES = 2L * 1024 * 1024;

    private ImageUtil() {
    }

    /** magic number：不信扩展名，只看文件头（PRD 6.8） */
    public static boolean looksLikeJpeg(byte[] head) {
        return head.length >= 3
                && (head[0] & 0xFF) == 0xFF
                && (head[1] & 0xFF) == 0xD8
                && (head[2] & 0xFF) == 0xFF;
    }

    public static boolean looksLikePng(byte[] head) {
        return head.length >= 8
                && (head[0] & 0xFF) == 0x89
                && head[1] == 'P'
                && head[2] == 'N'
                && head[3] == 'G';
    }

    /** 解不出图时返回 null（不是抛异常），调用方按「不是有效图片」处理 */
    public static BufferedImage read(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    /**
     * 写 large，返回实际落盘的 [宽, 高]。
     *
     * <p>三种情况：
     * <ol>
     *   <li>JPEG 且尺寸 ≤1280 且 ≤2MB → 原样写，零重压（正常路径）</li>
     *   <li>尺寸超标 → 缩到 1280 再写</li>
     *   <li>非 JPEG（如 PNG）→ 转 JPEG 写</li>
     * </ol>
     */
    public static int[] writeLarge(BufferedImage source, byte[] raw, boolean jpeg, Path target) throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        int maxEdge = Math.max(width, height);
        boolean oversized = maxEdge > LARGE_EDGE;

        if (jpeg && !oversized && raw.length <= PRE_COMPRESSED_BYTES) {
            Files.write(target, raw);
            return new int[]{width, height};
        }

        writeScaled(source, target, LARGE_EDGE, LARGE_QUALITY);
        if (!oversized) {
            return new int[]{width, height};
        }
        double scale = (double) LARGE_EDGE / maxEdge;
        return new int[]{(int) Math.round(width * scale), (int) Math.round(height * scale)};
    }

    /** thumb：最长边 400，保持比例，只压这一次 */
    public static void writeThumb(BufferedImage source, Path target) throws IOException {
        writeScaled(source, target, THUMB_EDGE, THUMB_QUALITY);
    }

    /** avatar：96×96 居中裁切（头像必须是方的） */
    public static void writeAvatar(BufferedImage source, Path target) throws IOException {
        Thumbnails.of(flatten(source))
                .size(AVATAR_SIZE, AVATAR_SIZE)
                .crop(Positions.CENTER)
                .outputQuality(AVATAR_QUALITY)
                .outputFormat("jpg")
                .toFile(target.toFile());
    }

    // ---------------------------------------------------------------- 内部实现

    private static void writeScaled(BufferedImage source, Path target, int maxEdge, float quality)
            throws IOException {
        BufferedImage flat = flatten(source);
        Thumbnails.Builder<BufferedImage> builder = Thumbnails.of(flat);
        int sourceMax = Math.max(source.getWidth(), source.getHeight());
        if (sourceMax > maxEdge) {
            // size 是「不超过」，会保持比例；不加这层判断的话 scale 会作用在裁剪后的图上
            builder.size(maxEdge, maxEdge);
        } else {
            // 小图不放大 —— Thumbnails.size 遇到小图默认会拉大，模糊得很难看
            builder.scale(1.0);
        }
        builder.outputQuality(quality).outputFormat("jpg").toFile(target.toFile());
    }

    /** 去掉 alpha 并铺白底：PNG 透明区域直接转 JPEG 会变黑 */
    private static BufferedImage flatten(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }
}
