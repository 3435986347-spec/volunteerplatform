package com.hengde.auth.integration;

import cn.hutool.core.util.IdcardUtil;
import com.hengde.auth.config.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 身份证二要素实名校验实现。
 *
 * <p>{@link AuthProperties#isRealnameEnabled()} 为 false（dev/test 默认）时，
 * 仅做身份证号格式校验后放行，不调用第三方；为 true 时应接入腾讯云核验（待密钥就绪）。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class RealNameServiceImpl implements RealNameService {

    private AuthProperties properties;

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
        // TODO 接入腾讯云身份证核验接口
        throw new UnsupportedOperationException("真实实名校验尚未接入");
    }
}
