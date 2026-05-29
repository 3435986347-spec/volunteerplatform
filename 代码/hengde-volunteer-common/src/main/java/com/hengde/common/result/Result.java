package com.hengde.common.result;

import lombok.Data;

/**
 * 统一返回结果。
 *
 * <p>所有 Controller 接口都返回这个对象，保证前端拿到的 JSON 结构永远一致：</p>
 * <pre>
 * { "code": 200, "message": "成功", "data": {...} }
 * </pre>
 *
 * <p>泛型 {@code T} 表示业务数据的类型，比如 {@code Result<UserVO>}、{@code Result<List<Xxx>>}；
 * 不需要返回数据时用 {@code Result<Void>}。</p>
 *
 * @param <T> 业务数据类型
 * @author hengde
 */
@Data
public class Result<T> {

    /** 状态码：200 成功，其余见 {@link ResultCode} */
    private Integer code;

    /** 提示信息 */
    private String message;

    /** 业务数据，失败时一般为 null */
    private T data;

    /**
     * 私有全参构造，外部统一通过下面的静态工厂方法创建，避免到处 new。
     */
    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功，但没有数据需要返回（如新增/删除操作）。
     *
     * @return code=200、message="成功"、data=null 的结果
     */
    public static <T> Result<T> ok() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    /**
     * 成功，并携带业务数据。
     *
     * @param data 要返回给前端的数据
     * @return code=200、message="成功" 的结果
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /**
     * 失败，自定义状态码和提示。
     *
     * @param code    自定义状态码
     * @param message 自定义提示
     * @return 失败结果（data=null）
     */
    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 失败，直接用错误码枚举（推荐：码和文案集中维护）。
     *
     * @param resultCode 错误码枚举
     * @return 失败结果（data=null）
     */
    public static <T> Result<T> fail(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }
}
