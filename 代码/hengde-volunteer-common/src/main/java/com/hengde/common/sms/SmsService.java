package com.hengde.common.sms;

import java.util.Map;

/**
 * 短信发送通道，封装火山引擎短信 SDK。
 *
 * <p>这是 common 提供的「传输层」能力：只负责把短信发出去，不掺杂业务。
 * 验证码的生成、存 Redis、校验、限流等逻辑归各业务领域（如 auth），
 * 业务侧组好模板参数后调用本接口即可。auth、user、publicity 等领域共用此通道。</p>
 *
 * @author hengde
 */
public interface SmsService {

    /** 验证码类短信在 {@link SmsProperties#getTemplates()} 中的模板键 */
    String TEMPLATE_VERIFY_CODE = "verify-code";

    /** 验证码模板中的占位参数名，需与火山控制台模板里的 {@code ${code}} 对应 */
    String PARAM_CODE = "code";

    /**
     * 通用发送：指定模板 ID 与模板参数发送短信。
     *
     * @param phone      接收手机号（火山支持逗号分隔多号，业务一般单号）
     * @param templateId 火山短信模板 ID
     * @param params     模板占位参数，键为模板中的变量名，值为替换内容
     */
    void send(String phone, String templateId, Map<String, String> params);

    /**
     * 便捷方法：发送验证码短信。
     *
     * <p>使用 {@link #TEMPLATE_VERIFY_CODE} 对应的模板，参数为 {@code {code: 验证码}}。
     * 验证码本身由调用方（业务领域）生成并自行存储/校验。</p>
     *
     * @param phone 接收手机号
     * @param code  验证码内容
     */
    void sendVerifyCode(String phone, String code);
}
