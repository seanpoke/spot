package ltd.sean.visit.controller;

import ltd.sean.visit.dto.ExportedFile;
import ltd.sean.visit.service.ExportService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 全量导出（设置页「备份」区块）。
 *
 * <p>三个端点都直接返回文件流，所以不走 {@code ApiResult}：
 * 浏览器点链接即下载，前端不需要 fetch + Blob（也就避开了「忘了带 Cookie」这类坑）。
 * 代价是失败时返回的是 JSON 错误体而不是页面 —— 单用户自用可以接受。
 *
 * <p>文件名由服务端定，前端 {@code <a download>} 不用再操心扩展名。
 */
@RestController
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    /** 全量 JSON 备份（四张表 + 关系） */
    @GetMapping("/export/json")
    public ResponseEntity<byte[]> json() {
        return download(exportService.json());
    }

    /** 打卡点 CSV（含标签、到访次数、平均分） */
    @GetMapping("/export/spots.csv")
    public ResponseEntity<byte[]> spotsCsv() {
        return download(exportService.spotsCsv());
    }

    /** 到访 CSV（带到访记录的全部分项与感受） */
    @GetMapping("/export/visits.csv")
    public ResponseEntity<byte[]> visitsCsv() {
        return download(exportService.visitsCsv());
    }

    private ResponseEntity<byte[]> download(ExportedFile file) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        // filename(...) 带 charset：中文文件名在部分浏览器上不加就是乱码
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build());
        headers.setCacheControl(CacheControl.noStore());
        return new ResponseEntity<>(file.content(), headers, HttpStatus.OK);
    }
}
