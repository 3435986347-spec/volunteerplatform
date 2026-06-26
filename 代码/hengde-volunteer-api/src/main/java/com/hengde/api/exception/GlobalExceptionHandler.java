package com.hengde.api.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

    // BusinessException 使用业务码，HTTP 状态固定 400，前端按 code 区分具体错误
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("未捕获异常", e);
        return Result.fail(500, "服务器内部错误");
    }
}
