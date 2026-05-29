package com.hengde.auth.integration;

/**
 * 身份证二要素实名校验（姓名 + 身份证号）。
 *
 * <p>V1 计划对接腾讯云身份证核验接口。当前为 no-op 实现（{@link RealNameServiceImpl}），
 * 拿到密钥后替换为真实实现即可，注册流程不变。</p>
 *
 * @author hengde
 */
public interface RealNameService {

    /**
     * 校验姓名与身份证号是否一致。
     *
     * @param realName 姓名
     * @param idCardNo 身份证号（明文）
     * @return 一致返回 true
     */
    boolean verify(String realName, String idCardNo);
}
