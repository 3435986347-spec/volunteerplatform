package com.hengde.api.config;

import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 企业端闸门的判定表（V4 爱心企业批）。<b>需本机 Docker。</b>
 *
 * <p>两个方向都会静默出错：放行清单少一条，待审核的企业连头像都补不了、审核永远过不了；多一条（比如写成 {@code /e/**}），
 * 没审核的企业就能发商品发帖。都不抛异常，只体现在某家企业身上。</p>
 *
 * @author hengde
 */
@SpringBootTest(classes = HengdeVolunteerApplication.class)
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class EnterpriseAccountGateTest {

    @Test
    void decisionTable() {
        assertEquals("企业账号不存在或已删除，请重新登录", EnterpriseAccountGate.problemOf("/e/enterprise/profile", null));
        assertEquals("企业账号已暂停，请联系平台", EnterpriseAccountGate.problemOf("/e/enterprise/profile", 3));
        assertEquals("企业账号已暂停，请联系平台", EnterpriseAccountGate.problemOf("/e/auth/logout", 3));
        for (int unapproved : new int[]{0, 2}) {
            assertNull(EnterpriseAccountGate.problemOf("/e/auth/logout", unapproved));
            assertNull(EnterpriseAccountGate.problemOf("/e/auth/password", unapproved));
            assertNull(EnterpriseAccountGate.problemOf("/e/enterprise/profile", unapproved));
            assertNull(EnterpriseAccountGate.problemOf("/e/enterprise/profile/resubmit", unapproved));
            assertNull(EnterpriseAccountGate.problemOf("/e/files/image", unapproved));
            assertEquals("入驻审核通过后才能使用该功能", EnterpriseAccountGate.problemOf("/e/donate/goods", unapproved));
            assertEquals("入驻审核通过后才能使用该功能", EnterpriseAccountGate.problemOf("/e/social/posts", unapproved));
            assertEquals("入驻审核通过后才能使用该功能", EnterpriseAccountGate.problemOf("/e/enterprise/profiles", unapproved));
        }
        assertNull(EnterpriseAccountGate.problemOf("/e/donate/goods", 1));
    }
}
