package com.hengde.common.sms;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import com.volcengine.model.request.SmsSendRequest;
import com.volcengine.model.response.ResponseMetadata;
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
 *     <li>只做传输，发送失败抛 {@link BusinessException} 交由全局异常处理器兜底；
 *         火山对业务错误（签名/模板未审核、账号欠费等）同样返回 HTTP 200 且 SDK 不抛异常，
 *         故额外显式校验 {@code ResponseMetadata.Error}，避免把失败当成功。</li>
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

        SmsSendResponse response;
        try {
            response = client().send(request);
        } catch (Exception e) {
            log.error("[SMS] 发送失败 phone={} templateId={}", phone, templateId, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "短信发送失败，请稍后重试");
        }
        // 业务错误校验刻意放在 catch 之外：若在 try 内抛 BusinessException，会被上面的 catch 重新包装、丢掉具体错误码
        requireAccepted(phone, templateId, response);
        log.info("[SMS] 已发送 phone={} templateId={} resp={}", phone, templateId, response);
    }

    @Override
    public void sendVerifyCode(String phone, String code, String scene) {
        send(phone, resolveVerifyCodeTemplate(scene), Map.of(PARAM_CODE, code));
    }

    /**
     * 按场景解析验证码模板 ID：优先取场景专属模板，未配置则回退到兜底模板。
     *
     * <p>场景值（{@link SmsScene}）直接作为 {@link SmsProperties#getTemplates()} 的键，
     * 因此运营方只需在配置里加一行 {@code register: ST_xxx} 就能给注册单独配文案、无需改代码；
     * 只配 {@code verify-code} 一条则所有场景共用它。</p>
     *
     * @param scene 场景，可为空
     * @return 模板 ID；两级都没配时返回 null，由 {@link #send} 统一报「短信模板未配置」
     */
    private String resolveVerifyCodeTemplate(String scene) {
        Map<String, String> templates = properties.getTemplates();
        if (StringUtils.hasText(scene)) {
            String sceneTemplate = templates.get(scene);
            if (StringUtils.hasText(sceneTemplate)) {
                return sceneTemplate;
            }
            log.debug("[SMS] 场景 {} 未单独配置模板，回退到兜底模板 {}", scene, TEMPLATE_VERIFY_CODE);
        }
        return templates.get(TEMPLATE_VERIFY_CODE);
    }

    /**
     * 校验火山是否真的受理了本次发送，未受理则抛业务异常。
     *
     * <p>火山对业务错误（{@code RE:0004} 签名错误 / {@code RE:0005} 模板错误 / {@code RE:0010} 账号欠费 /
     * {@code RE:0006} 手机号格式错误等）同样返回 <b>HTTP 200</b>，而 SDK 的 {@code send} 仅在 HTTP 传输层
     * 失败（{@code SdkError.EHTTP}）时抛异常，这类错误不会走到调用处的 catch 分支。不显式检查就会把失败
     * 当成功、日志打「已发送」，而用户永远收不到短信，排查成本极高。</p>
     *
     * <p>对外只抛通用文案，不透出服务商错误码；真实错误码、原文与 requestId 记入日志供排查。</p>
     *
     * @param phone      接收手机号，仅用于日志定位
     * @param templateId 短信模板 ID，仅用于日志定位
     * @param response   火山返回的响应体
     */
    private void requireAccepted(String phone, String templateId, SmsSendResponse response) {
        if (response == null) {
            log.error("[SMS] 发送失败 phone={} templateId={}：火山返回空响应", phone, templateId);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "短信发送失败，请稍后重试");
        }

        ResponseMetadata metadata = response.getResponseMetadata();
        ResponseMetadata.Error error = (metadata == null) ? null : metadata.getError();
        String errorCode = null;
        String errorMessage = null;
        if (error != null && StringUtils.hasText(error.getCode())) {
            errorCode = error.getCode();
            errorMessage = error.getMessage();
        } else if (StringUtils.hasText(response.getCode())) {
            // SDK 少数失败分支不填 ResponseMetadata.Error，而是直接置顶层 code/message，一并兜住
            errorCode = response.getCode();
            errorMessage = response.getMessage();
        }
        if (errorCode == null) {
            return;
        }

        log.error("[SMS] 发送被拒 phone={} templateId={} errorCode={} errorMessage={} requestId={}",
                phone, templateId, errorCode, errorMessage,
                (metadata == null) ? null : metadata.getRequestId());
        throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "短信发送失败，请稍后重试");
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
