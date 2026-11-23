package com.hengde.api.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleNotLogin(NotLoginException e) {
        return Result.fail(401, "请先登录");
    }

    @ExceptionHandler(NotPermissionException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleNotPermission(NotPermissionException e) {
        return Result.fail(403, "无操作权限");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(400, msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(cv -> cv.getMessage())
                .collect(Collectors.joining("; "));
        return Result.fail(400, msg);
    }

    // 请求体格式错误：JSON 语法错误、字段类型解析失败、未知字段、缺/坏请求体。
    // 尽量回出错字段路径（如 lat / slots[0].needCount），便于前端/联调定位是哪个字段类型不匹配或多传，
    // 而非笼统「请求体格式错误」；具体技术细节只记服务端日志，不外泄。
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        String field = extractFieldPath(e.getCause());
        log.warn("请求体解析失败{}: {}", field == null ? "" : "（字段 " + field + "）", e.getMostSpecificCause().getMessage());
        return Result.fail(400, field == null ? "请求体格式错误" : "请求体格式错误：字段「" + field + "」类型或取值不正确");
    }

    /** 从 Jackson 映射异常里抽出出错字段路径（a.b[0].c），无则 null。 */
    private String extractFieldPath(Throwable cause) {
        if (!(cause instanceof JsonMappingException jme) || jme.getPath() == null || jme.getPath().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonMappingException.Reference ref : jme.getPath()) {
            if (ref.getFieldName() != null) {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(ref.getFieldName());
            } else {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // 路径变量/请求参数类型不匹配，如 /activities/abc（abc 无法转 Long）
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.fail(400, "请求参数格式错误");
    }

    /*
     * 下面三类是「请求本身就不对」，此前都落进最后的兜底 → HTTP 500「服务器内部错误」+ 一条 ERROR 堆栈：
     *   · 路径不存在（NoResourceFoundException）       → 应为 404
     *   · 方法不对，如对只收 GET 的路径发 PUT           → 应为 405
     *   · 缺 @RequestParam 必填参数                     → 应为 400，并说出缺的是哪个
     * 后台控制台拼错路径 / 漏传参数时看到的是「服务器内部错误」，会让人去查服务端代码而不是查调用方；
     * 被扫描器扫一圈，ERROR 日志里全是这种噪音，把真正的故障淹掉。（控制台 V3 商城批联调时撞出：
     * 查积分总览写错成 /a/activity/points/summary，回来的是 500。）
     * 记 WARN、不打堆栈——与业务拒绝同一口径。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        log.warn("接口不存在 {} {}", request.getMethod(), request.getRequestURI());
        return Result.fail(404, "接口不存在");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        log.warn("请求方法不支持 {} {}", request.getMethod(), request.getRequestURI());
        return Result.fail(405, "请求方法不支持：" + e.getMethod());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e, HttpServletRequest request) {
        log.warn("缺少必填参数 {} {} → {}", request.getMethod(), request.getRequestURI(), e.getParameterName());
        return Result.fail(400, "缺少必填参数：" + e.getParameterName());
    }

    // BusinessException 使用业务码，HTTP 状态固定 400，前端按 code 区分具体错误
    /**
     * 业务拒绝 → HTTP 400。<b>必须留一行日志</b>。
     *
     * <p>此前这里一行日志都不打：前端拿到 400 和一个 {@code X-Trace-Id}，拿着 traceId 去服务器日志里
     * 却 grep 不到任何东西——traceId 在日志格式里（{@code [%X{traceId}]}），可这一次请求压根没写过日志。
     * 于是「证书下载 400」这种问题只能靠猜，或者让测试的人把响应体一个字一个字抄回来。</p>
     *
     * <p>记 WARN 不记 ERROR：这是业务按规则拒绝，不是程序出错。
     * 带上请求方法与路径，否则同一句「证书不存在」分不清是哪个接口报的。
     * <b>不打堆栈</b>——业务拒绝的堆栈没有信息量，只会把真正的 ERROR 淹掉。</p>
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBusiness(BusinessException e, HttpServletRequest request) {
        log.warn("业务拒绝 {} {} → code={} message={}",
                request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("未捕获异常", e);
        return Result.fail(500, "服务器内部错误");
    }
}
