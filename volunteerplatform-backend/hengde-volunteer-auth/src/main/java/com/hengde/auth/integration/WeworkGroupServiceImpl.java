package com.hengde.auth.integration;

import com.hengde.auth.config.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 企业微信群成员校验实现。
 *
 * <p>{@link AuthProperties#isWeworkGroupEnabled()} 为 false（dev/test 默认）时直接放行；
 * 为 true 时应接入企业微信群成员查询（待接入）。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class WeworkGroupServiceImpl implements WeworkGroupService {

    private AuthProperties properties;

    @Autowired
    public void setProperties(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isGroupMember(String phone) {
        if (!properties.isWeworkGroupEnabled()) {
            log.info("[Wework-MOCK] 未启用真实企业微信群校验，直接放行 phone={}", phone);
            return true;
        }
        // TODO 接入企业微信群成员查询
        throw new UnsupportedOperationException("真实企业微信群校验尚未接入");
    }

    @Override
    public String getGroupQrUrl() {
        return properties.getWeworkGroupQrUrl();
    }
}
