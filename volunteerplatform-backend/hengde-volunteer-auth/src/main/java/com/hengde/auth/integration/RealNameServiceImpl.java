package com.hengde.auth.integration;

import cn.hutool.core.util.IdcardUtil;
import com.hengde.auth.config.AuthProperties;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.faceid.v20180301.FaceidClient;
import com.tencentcloudapi.faceid.v20180301.models.IdCardVerificationRequest;
import com.tencentcloudapi.faceid.v20180301.models.IdCardVerificationResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 身份证二要素实名校验实现，接腾讯云 faceid 的
 * <a href="https://cloud.tencent.com/document/api/1007/33188">IdCardVerification</a>（Version 2018-03-01）。
 *
 * <p>{@link AuthProperties#isRealnameEnabled()} 为 false（dev/test 默认）时，
 * 仅做身份证号格式校验后放行，不调用第三方、不产生费用；为 true 时走真实核验。
 * 客户端按密钥懒加载一次后复用。</p>
 *
 * <p><b>返回码分两类处理，不可混为一谈</b>：{@code 0} 通过、{@code -1/-2/-3/-5} 是入参本身对不上
 * （如实返回 false）；而 {@code -4} 证书库异常、{@code -6} 权威系统维护、{@code -7} 当日额度用尽
 * 属于<b>服务侧问题，与用户填写的信息无关</b>——这类若也返回 false，用户会看到「实名认证失败，
 * 请核对身份信息」，但他信息是对的，只会反复重试且无从排查，故统一抛业务异常提示稍后再试。
 * 未知返回码按同样口径处理（不归因到用户）。</p>
 *
 * <p>日志只记姓名与 requestId，<b>不打身份证号</b>（PII，库里都是 AES-GCM 密文存储）。
 * requestId 是找腾讯云客服排查时要提供的关键信息。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class RealNameServiceImpl implements RealNameService {

    /** 姓名与身份证号一致（该结果计费） */
    private static final String RESULT_MATCHED = "0";

    /**
     * 判定为「信息不符」的返回码：{@code -1} 二者不一致（计费）、{@code -2} 身份证格式错、
     * {@code -3} 姓名格式错、{@code -5} 库中无此身份证记录。均为入参问题，如实告知用户未通过。
     */
    private static final Set<String> RESULT_REJECTED = Set.of("-1", "-2", "-3", "-5");

    private static final String SERVICE_UNAVAILABLE_MESSAGE = "实名认证服务暂不可用，请稍后重试";

    private AuthProperties properties;

    /** 腾讯云 faceid 客户端，首次真实核验时懒加载 */
    private volatile FaceidClient faceidClient;

    @Autowired
    public void setProperties(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean verify(String realName, String idCardNo) {
        if (!StringUtils.hasText(realName) || !IdcardUtil.isValidCard(idCardNo)) {
            return false;
        }
        if (!properties.isRealnameEnabled()) {
            log.info("[RealName-MOCK] 未启用真实实名校验，直接放行 name={}", realName);
            return true;
        }

        IdCardVerificationRequest request = new IdCardVerificationRequest();
        request.setName(realName);
        request.setIdCard(idCardNo);

        IdCardVerificationResponse response;
        try {
            response = client().IdCardVerification(request);
        } catch (TencentCloudSDKException e) {
            log.error("[RealName] 调用腾讯云核验接口失败 name={}", realName, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), SERVICE_UNAVAILABLE_MESSAGE);
        }
        return interpret(response, realName);
    }

    /** 按返回码判定结果；服务侧异常与未知码一律抛业务异常，不当成「用户信息不符」 */
    private boolean interpret(IdCardVerificationResponse response, String realName) {
        String result = response == null ? null : response.getResult();
        String description = response == null ? null : response.getDescription();
        String requestId = response == null ? null : response.getRequestId();

        if (RESULT_MATCHED.equals(result)) {
            log.info("[RealName] 核验通过 name={} requestId={}", realName, requestId);
            return true;
        }
        if (RESULT_REJECTED.contains(result)) {
            log.info("[RealName] 核验不通过 name={} result={} desc={} requestId={}",
                    realName, result, description, requestId);
            return false;
        }
        // -4 证书库异常 / -6 权威系统维护 / -7 当日额度用尽，以及任何未知码
        log.error("[RealName] 核验服务异常 name={} result={} desc={} requestId={}",
                realName, result, description, requestId);
        throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), SERVICE_UNAVAILABLE_MESSAGE);
    }

    /** 懒加载腾讯云客户端：双重检查，保证只构建一次并复用 */
    private FaceidClient client() {
        if (faceidClient == null) {
            synchronized (this) {
                if (faceidClient == null) {
                    if (!StringUtils.hasText(properties.getRealnameSecretId())
                            || !StringUtils.hasText(properties.getRealnameSecretKey())) {
                        log.error("[RealName] 已启用实名校验但未配置腾讯云密钥");
                        throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), SERVICE_UNAVAILABLE_MESSAGE);
                    }
                    Credential credential = new Credential(
                            properties.getRealnameSecretId(), properties.getRealnameSecretKey());
                    // IdCardVerification 不需要 Region，此处仅为满足 SDK 客户端构造
                    faceidClient = new FaceidClient(credential, properties.getRealnameRegion());
                }
            }
        }
        return faceidClient;
    }
}
