package com.hengde.common.exception;

import com.hengde.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>用于「可预期」的错误，比如「用户不存在」「验证码错误」。业务代码里直接
 * {@code throw new BusinessException(ResultCode.USER_NOT_FOUND)}，
 * 由 api 模块的全局异常处理器统一捕获并转成 {@link com.hengde.common.result.Result} 返回。</p>
 *
 * <p>继承自 {@link RuntimeException}（非受检异常），所以不用在方法签名上到处 {@code throws}。</p>
 *
 * @author hengde
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务状态码，对应 {@link ResultCode#getCode()} */
    private final Integer code;

    /**
     * 自定义状态码 + 提示。
     *
     * @param code    状态码
     * @param message 提示信息（会作为异常 message）
     */
    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 只给提示信息，状态码默认 400（请求参数错误）。
     *
     * @param message 提示信息
     */
    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.BAD_REQUEST.getCode();
    }

    /**
     * 直接用错误码枚举抛出（最常用，码和文案都来自枚举）。
     *
     * @param resultCode 错误码枚举
     */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }
}
