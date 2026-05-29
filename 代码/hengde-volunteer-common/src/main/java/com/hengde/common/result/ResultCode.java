package com.hengde.common.result;

import lombok.Getter;

/**
 * 统一错误码枚举。
 *
 * <p>每个枚举项 = 一个「业务结果」，由两部分组成：</p>
 * <ul>
 *     <li>code：状态码，前端/调用方靠它判断结果类型（200 系=成功，其余=各类失败）；</li>
 *     <li>message：给人看的中文提示。</li>
 * </ul>
 *
 * <p>约定：400~599 沿用 HTTP 语义；1000 起为业务自定义码，避免和 HTTP 码混淆。</p>
 *
 * @author hengde
 */
@Getter
public enum ResultCode {

    /** 成功 */
    SUCCESS(200, "成功"),

    /** 请求参数错误（语义上的 HTTP 400） */
    BAD_REQUEST(400, "请求参数错误"),
    /** 未登录或登录已过期 */
    UNAUTHORIZED(401, "未登录或登录已过期"),
    /** 已登录但无权访问该资源 */
    FORBIDDEN(403, "无权访问"),
    /** 资源不存在 */
    NOT_FOUND(404, "资源不存在"),
    /** 服务器内部错误（兜底） */
    SERVER_ERROR(500, "服务器内部错误"),

    /** 通用业务异常 */
    BUSINESS_ERROR(1000, "业务异常"),
    /** 参数校验未通过（如 @Valid 校验失败） */
    PARAM_ERROR(1001, "参数校验失败"),

    /** 用户不存在 */
    USER_NOT_FOUND(2001, "用户不存在"),
    /** 用户已存在（重复注册等） */
    USER_ALREADY_EXISTS(2002, "用户已存在"),
    /** 密码错误 */
    PASSWORD_ERROR(2003, "密码错误"),
    /** 短信验证码错误或已过期 */
    SMS_CODE_ERROR(2004, "验证码错误或已过期");

    /** 状态码 */
    private final Integer code;

    /** 提示信息 */
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
