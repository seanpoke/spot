package ltd.sean.visit.service;

import ltd.sean.visit.common.BizException;
import ltd.sean.visit.config.AppProperties;
import ltd.sean.visit.dto.UploadedImage;
import ltd.sean.visit.util.ImageUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

/**
 * 图片上传：校验 → 落盘三档 → 返回可直接用的 URL。
 *
 * <p>目录结构（PRD 6.7）：
 * <pre>
 * uploads/YYYY/MM/&lt;uuid&gt;_l.jpg   large
 * uploads/YYYY/MM/&lt;uuid&gt;_t.jpg   thumb
 * uploads/avatars/&lt;uuid&gt;_a.jpg   avatar
 * </pre>
 *
 * <p>数据库里只存相对路径，绝对地址一律由 {@link AppProperties#toPublicUrl} 在出参时拼 ——
 * 这正是「本地不走 CDN、线上走 CDN」不用改任何代码的原因。
 *
 * <p>不保留原图（PRD 6.6）：原图在手机相册里，系统里再存一份只是浪费磁盘。
 */
@Slf4j
@Service
public class UploadService {

    /** 文件头取前 8 字节就够判断 JPEG / PNG */
    private static final int MAGIC_LENGTH = 8;

    /** 头像派生图的固定目录（96×96 方图，与按月分目录的 large/thumb 分开放） */
    private static final String AVATAR_DIR = "/uploads/avatars";

    /**
     * 头像派生图的相对路径。
     *
     * <p>这一档不落库（photo 表只存 large/thumb），但「没设头像时拿第一张照片顶上」需要它 ——
     * 目录与命名只在这里写一次，别在别处再拼一遍。
     */
    public static String avatarPath(String imageId) {
        return AVATAR_DIR + "/" + imageId + "_a.jpg";
    }

    private final AppProperties appProperties;

    public UploadService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public UploadedImage save(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "没有收到文件");
        }

        int maxMb = appProperties.uploadMaxMb();
        if (maxMb > 0 && file.getSize() > (long) maxMb * 1024 * 1024) {
            throw new BizException(400, "图片太大，单张请控制在 " + maxMb + "MB 以内");
        }

        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException e) {
            throw new BizException(400, "读取上传内容失败");
        }

        byte[] head = Arrays.copyOf(raw, Math.min(raw.length, MAGIC_LENGTH));
        boolean jpeg = ImageUtil.looksLikeJpeg(head);
        if (!jpeg && !ImageUtil.looksLikePng(head)) {
            throw new BizException(400, "只支持 JPEG / PNG 图片");
        }

        BufferedImage source;
        try {
            source = ImageUtil.read(raw);
        } catch (IOException e) {
            throw new BizException(400, "图片解析失败");
        }
        if (source == null) {
            throw new BizException(400, "不是有效的图片文件");
        }

        String id = UUID.randomUUID().toString().replace("-", "");
        LocalDate today = LocalDate.now();
        String monthDir = String.format("%04d/%02d", today.getYear(), today.getMonthValue());

        Path root = uploadRoot();
        Path monthPath = root.resolve(monthDir);
        Path avatarPath = root.resolve("avatars");
        Path largeFile = monthPath.resolve(id + "_l.jpg");
        Path thumbFile = monthPath.resolve(id + "_t.jpg");
        Path avatarFile = avatarPath.resolve(id + "_a.jpg");

        try {
            Files.createDirectories(monthPath);
            Files.createDirectories(avatarPath);
            int[] size = ImageUtil.writeLarge(source, raw, jpeg, largeFile);
            ImageUtil.writeThumb(source, thumbFile);
            ImageUtil.writeAvatar(source, avatarFile);
            long bytes = Files.size(largeFile);

            return new UploadedImage(
                    id,
                    appProperties.toPublicUrl("/uploads/" + monthDir + "/" + id + "_l.jpg"),
                    appProperties.toPublicUrl("/uploads/" + monthDir + "/" + id + "_t.jpg"),
                    appProperties.toPublicUrl(avatarPath(id)),
                    size[0],
                    size[1],
                    bytes);
        } catch (IOException e) {
            // 落盘失败已经把半成品写进去了也无所谓：文件名是 UUID，永远不会有请求指向它
            log.error("图片落盘失败 dir={}", monthDir, e);
            throw new BizException(500, "保存图片失败，请重试");
        }
    }

    /** 与 WebMvcConfig 的静态映射保持同一套解析规则：相对路径按启动目录解析 */
    private Path uploadRoot() {
        String configured = appProperties.uploadDir();
        String dir = (configured == null || configured.isBlank()) ? "./uploads" : configured;
        return Paths.get(dir).toAbsolutePath().normalize();
    }
}
