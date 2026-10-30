package ltd.sean.visit.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常出口：任何异常都在这里变成 {@link ApiResult}，Controller 里不写 try/catch。
 *
 * <p>三条约定：
 * <ol>
 *   <li>业务异常按自带 code 返回，消息可以直接给用户看</li>
 *   <li>Redis 连不上返回 503（不是 500，也不是放行）</li>
 *   <li>其余异常只记日志、对外返回笼统文案，不把堆栈或 SQL 泄露出去</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResult<Void>> handleBiz(BizException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getCode()).body(ApiResult.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<ApiResult<Void>> handleRedisDown(RedisConnectionFailureException e) {
        // 绝不降级放行：看起来「友好」的容错等于把私有数据敞开
        log.error("Redis 不可用：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResult.fail(HttpStatus.SERVICE_UNAVAILABLE.value(), "缓存服务不可用，请稍后重试"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResult<Void>> handleDb(DataAccessException e) {
        log.error("数据库访问失败", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "数据访问失败"));
    }

    /**
     * Spring 6.1 起，静态资源（/uploads/**）找不到文件抛的是 {@link NoResourceFoundException}，
     * 它继承自 ServletException，会被下面的兜底分支当成 500。路径打错时看到 500 会误导排查，
     * 这里显式转成 404。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.fail(HttpStatus.NOT_FOUND.value(), "资源不存在"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError first = e.getBindingResult().getFieldError();
        // DTO 上的约束都写了中文 message，直接用它；没写的才退化成「字段名 不合法」，
        // 不要无条件拼字段名 —— 那会变成「name 名称不能为空」这种半中半英的提示
        String msg = "参数不合法";
        if (first != null) {
            String detail = first.getDefaultMessage();
            msg = (detail == null || detail.isBlank()) ? first.getField() + " 不合法" : detail;
        }
        return ResponseEntity.badRequest().body(ApiResult.fail(HttpStatus.BAD_REQUEST.value(), msg));
    }

    /**
     * 请求体不是合法 JSON（或字段类型对不上）时 Spring 抛这个，
     * 不兜住会走 500，前端只能看到「服务内部错误」，排查时白费时间。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiResult.fail(HttpStatus.BAD_REQUEST.value(), "请求体格式不正确"));
    }

    /**
     * 查询参数缺失（例如地图视野查询漏了 maxLat）或类型不对（page=abc）。
     *
     * <p>不兜会走 500「服务内部错误」，前端拼错参数时根本看不出是自己漏传了。
     * 这类是调用方的问题，一律 400。
     */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResult<Void>> handleBadParam(Exception e) {
        String name = e instanceof MissingServletRequestParameterException missing
                ? missing.getParameterName()
                : ((MethodArgumentTypeMismatchException) e).getName();
        return ResponseEntity.badRequest()
                .body(ApiResult.fail(HttpStatus.BAD_REQUEST.value(), "参数不合法：" + name));
    }

    /**
     * 上传超限。Tomcat 在进 Controller 之前就把请求拒了，Service 里那句大小校验
     * 根本轮不到执行，所以必须在这里兜，否则前端看到的是 500。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResult<Void>> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResult.fail(HttpStatus.PAYLOAD_TOO_LARGE.value(), "图片太大，请压缩后再上传"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleOther(Exception e) {
        log.error("未预期的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务内部错误"));
    }
}
