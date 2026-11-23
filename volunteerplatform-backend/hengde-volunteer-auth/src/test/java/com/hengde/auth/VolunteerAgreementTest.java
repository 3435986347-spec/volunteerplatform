package com.hengde.auth;

import com.hengde.auth.service.VolunteerAuthService;
import com.hengde.auth.vo.AgreementVO;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 志愿者协议阅读：返回配置中的版本号与正文（注册时该版本随手写签名入库）。
 * MySQL 由 Testcontainers 起。<b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class VolunteerAgreementTest {

    @Autowired
    private VolunteerAuthService volunteerAuthService;

    @Test
    void agreement_returnsConfiguredVersionAndText() {
        AgreementVO vo = volunteerAuthService.getAgreement();
        assertEquals("1.0", vo.version(), "默认协议版本 1.0");
        assertNotNull(vo.text());
        assertTrue(!vo.text().isBlank(), "协议正文不应为空（默认占位文本）");
    }
}
