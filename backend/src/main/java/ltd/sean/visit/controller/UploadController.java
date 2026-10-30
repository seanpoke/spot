package ltd.sean.visit.controller;

import ltd.sean.visit.common.ApiResult;
import ltd.sean.visit.dto.UploadedImage;
import ltd.sean.visit.service.UploadService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片上传，实际路径 {@code /api/upload/image}（要口令）。
 *
 * <p>一次上传生成三档（large / thumb / avatar），前端按用途取，不用为了头像再传一次。
 * 单次一张，多图由前端逐张传（PRD 6.5：并发限 2，避免打满 3Mbps 带宽）。
 */
@RestController
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/upload/image")
    public ApiResult<UploadedImage> upload(@RequestParam("file") MultipartFile file) {
        return ApiResult.ok(uploadService.save(file));
    }
}
