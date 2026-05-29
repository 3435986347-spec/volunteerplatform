package com.hengde.auth.service;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理团队标记开关验证：置位/取消、操作人审计、幂等、非法值拒绝、游客置位拒绝但取消放行、不存在拒绝。
 *
 * <p>schema 由 common 的 Flyway 迁移在 Testcontainers MySQL 建库时执行。<b>需本机有 Docker。</b></p>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class VolunteerAdminServiceTest {

    private static final long OPERATOR_A = 1001L;
    private static final long OPERATOR_B = 1002L;

    @Autowired
    private VolunteerAdminService volunteerAdminService;
    @Autowired
    private VolunteerQueryService volunteerQueryService;
    @Autowired
    private VolunteerMapper volunteerMapper;

    @Test
    void setManagerFlag_onThenOff_recordsOperator() {
        Long id = insertVolunteer(true);
        assertFalse(volunteerQueryService.isManager(id), "初始未标记");

        volunteerAdminService.setManagerFlag(id, 1, OPERATOR_A);
        Volunteer afterOn = volunteerMapper.selectById(id);
        assertTrue(volunteerQueryService.isManager(id), "置位后应为管理团队");
        assertEquals(1, afterOn.getManagerFlag());
        assertEquals(OPERATOR_A, afterOn.getManagerFlagBy(), "应记录置位操作人");
        assertNotNull(afterOn.getManagerFlagTime(), "应记录置位时间");

        volunteerAdminService.setManagerFlag(id, 0, OPERATOR_B);
        Volunteer afterOff = volunteerMapper.selectById(id);
        assertFalse(volunteerQueryService.isManager(id), "取消后应回到非管理团队");
        assertEquals(0, afterOff.getManagerFlag());
        assertEquals(OPERATOR_B, afterOff.getManagerFlagBy(), "应记录取消操作人");
    }

    @Test
    void setManagerFlag_idempotentRepeatOn() {
        Long id = insertVolunteer(true);
        volunteerAdminService.setManagerFlag(id, 1, OPERATOR_A);
        // 重复设同值不报错、值不变
        volunteerAdminService.setManagerFlag(id, 1, OPERATOR_B);
        assertEquals(1, volunteerMapper.selectById(id).getManagerFlag());
    }

    @Test
    void setManagerFlag_illegalFlagRejected() {
        Long id = insertVolunteer(true);
        for (Integer bad : new Integer[]{null, 2, -1}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> volunteerAdminService.setManagerFlag(id, bad, OPERATOR_A),
                    "非法标记值应被拒绝：" + bad);
            assertEquals("管理团队标记值只能为 0 或 1", ex.getMessage());
        }
        // 非法值不应静默改动标记
        assertEquals(0, volunteerMapper.selectById(id).getManagerFlag());
    }

    @Test
    void setManagerFlag_guestSetOnRejected() {
        Long id = insertVolunteer(false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> volunteerAdminService.setManagerFlag(id, 1, OPERATOR_A), "游客不应可标记为管理团队");
        assertEquals("仅已实名志愿者可标记为管理团队", ex.getMessage());
    }

    @Test
    void setManagerFlag_guestCancelAllowed_cleansDirtyFlag() {
        // 模拟历史脏数据：游客身上误置了 manager_flag=1
        Long id = insertVolunteer(false);
        Volunteer dirty = new Volunteer();
        dirty.setId(id);
        dirty.setManagerFlag(1);
        volunteerMapper.updateById(dirty);

        // 取消(0)不要求已实名，应可清理
        volunteerAdminService.setManagerFlag(id, 0, OPERATOR_A);
        assertEquals(0, volunteerMapper.selectById(id).getManagerFlag());
    }

    @Test
    void setManagerFlag_notExistRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> volunteerAdminService.setManagerFlag(99999999L, 1, OPERATOR_A));
        assertEquals("志愿者不存在", ex.getMessage());
    }

    private Long insertVolunteer(boolean realName) {
        Volunteer v = new Volunteer();
        v.setOpenid("ok_" + System.nanoTime());
        v.setRealName("测试_" + System.nanoTime());
        if (realName) {
            v.setRegisterTime(LocalDateTime.now());
        }
        volunteerMapper.insert(v);
        return v.getId();
    }
}
