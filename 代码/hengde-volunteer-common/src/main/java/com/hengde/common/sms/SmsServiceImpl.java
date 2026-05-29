package com.hengde.common.sms;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import com.volcengine.model.request.SmsSendRequest;
import com.volcengine.model.response.SmsSendResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 短信发送通道实现，封装火山引擎 all-in-one SDK（volc-sdk-java）的短信能力。
 *
 * <p>设计要点：</p>
 * <ul>
 *     <li>{@link SmsProperties#isEnabled()} 为 false 时不调用 SDK，只打日志，
 *         便于本地开发与 Testcontainers 测试在无真实凭证下跑通；</li>
 *     <li>火山客户端按 AK/SK/Region 懒加载一次，之后复用；</li>
 *     <li>只做传输，发送失败抛 {@link BusinessException} 交由全局异常处理器兜底。</li>
 * </ul>
 *
 * <p>依赖按项目约定用 setter 注入，{@code @Autowired} 标在手写 setter 上。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class SmsServiceImpl implements SmsService {

    private SmsProperties properties;

    /** 火山短信客户端，首次真实发送时懒加载 */
    private volatile com.volcengine.service.sms.SmsService client;

    @Autowired
    public void setProperties(SmsProperties properties) {
        this.properties = properties;
    }

    @Override
    public void send(String phone, String templateId, Map<String, String> params) {
        if (!properties.isEnabled()) {
            log.info("[SMS-MOCK] 未启用真实发送，phone={} templateId={} params={}", phone, templateId, params);
            return;
        }
        if (!StringUtils.hasText(templateId)) {
            throw new BusinessException("短信模板未配置");
        }

        SmsSendRequest request = new SmsSendRequest();
        request.setSmsAccount(properties.getSmsAccount());
        request.setSign(properties.getSignName());
        request.setTemplateId(templateId);
        request.setPhoneNumbers(phone);
        if (params != null && !params.isEmpty()) {
            request.setTemplateParamByMap(params);
        }

        try {
            SmsSendResponse response = client().send(request);
            log.info("[SMS] 已发送 phone={} templateId={} resp={}", phone, templateId, response);
        } catch (Exception e) {
            log.error("[SMS] 发送失败 phone={} templateId={}", phone, templateId, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "短信发送失败，请稍后重试");
        }
    }

    @Override
    public void sendVerifyCode(String phone, String code) {
        String templateId = properties.getTemplates().get(TEMPLATE_VERIFY_CODE);
        send(phone, templateId, Map.of(PARAM_CODE, code));
    }

    /** 懒加载火山短信客户端：双重检查，保证只构建一次并复用 */
    private com.volcengine.service.sms.SmsService client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    com.volcengine.service.sms.SmsService c =
                            com.volcengine.service.sms.impl.SmsServiceImpl.getInstance();
                    c.setAccessKey(properties.getAccessKey());
                    c.setSecretKey(properties.getSecretKey());
                    if (StringUtils.hasText(properties.getRegion())) {
                        c.setRegion(properties.getRegion());
                    }
                    client = c;
                }
            }
        }
        return client;
    }
}
