package ltd.sean.visit.dto;

/**
 * 一个待下载的导出文件。
 *
 * <p>Service 只负责「文件名 + MIME + 字节」这三件事，把 HTTP 头留给 Controller，
 * 这样导出逻辑可以单独测，也不必知道 {@code Content-Disposition} 怎么写。
 */
public record ExportedFile(
        String filename,
        String contentType,
        byte[] content
) {
}
