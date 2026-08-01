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

    /**
     * 验证码类短信的<b>兜底</b>模板键（在 {@link SmsProperties#getTemplates()} 中）。
     *
     * <p>各场景可用自己的场景值作键单独配模板（见 {@link #sendVerifyCode(String, String, String)}），
     * 没单独配的场景回退到本键对应的模板。</p>
     */
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
     * 便捷方法：发送验证码短信，不区分场景（直接用 {@link #TEMPLATE_VERIFY_CODE} 兜底模板）。
     *
     * @param phone 接收手机号
     * @param code  验证码内容
     */
    default void sendVerifyCode(String phone, String code) {
        sendVerifyCode(phone, code, null);
    }

    /**
     * 按场景发送验证码短信，参数为 {@code {code: 验证码}}。
     * 验证码本身由调用方（业务领域）生成并自行存储/校验。
     *
     * <p><b>模板按场景选取</b>：先取 {@link SmsProperties#getTemplates()} 中键为 {@code scene}
     * 的模板（场景取值见 {@link SmsScene}，其字符串值<b>直接用作模板键</b>），未配置时回退到
     * {@link #TEMPLATE_VERIFY_CODE}。这样运营方既可以给「注册/登录/找回密码/换绑手机号」各配一条
     * 文案贴切的模板（避免注册时收到「您正在登录」这类错配文案），也可以只配一条通用模板通吃；
     * 新增场景只需加一行配置，无需改代码。</p>
     *
     * <p>模板参数固定为 {@link #PARAM_CODE}，因此<b>每个场景的模板变量名都必须是 {@code code}</b>
     * （即控制台模板正文里写 <code>${code}</code>）。</p>
     *
     * @param phone 接收手机号
     * @param code  验证码内容
     * @param scene 场景，见 {@link SmsScene}；为空则直接用兜底模板
     */
    void sendVerifyCode(String phone, String code, String scene);
}
