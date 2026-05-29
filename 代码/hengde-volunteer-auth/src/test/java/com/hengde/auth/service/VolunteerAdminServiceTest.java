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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理团队标记开关验证：置位/取消、幂等、游客与不存在志愿者拒绝。
 *
 * <p>schema 由 common 的 Flyway 迁移在 Testcontainers MySQL 建库时执行。<b>需本机有 Docker。</b></p>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class VolunteerAdminServiceTest {

    @Autowired
    private VolunteerAdminService volunteerAdminService;
    @Autowired
    private VolunteerQueryService volunteerQueryService;
    @Autowired
    private VolunteerMapper volunteerMapper;

    @Test
    void setManagerFlag_onThenOff() {
        Long id = insertVolunteer(true);
        assertFalse(volunteerQueryService.isManager(id), "初始未标记");

        volunteerAdminService.setManagerFlag(id, 1);
        assertTrue(volunteerQueryService.isManager(id), "置位后应为管理团队");
        assertEquals(1, volunteerMapper.selectById(id).getManagerFlag());

        volunteerAdminService.setManagerFlag(id, 0);
        assertFalse(volunteerQueryService.isManager(id), "取消后应回到非管理团队");
        assertEquals(0, volunteerMapper.selectById(id).getManagerFlag());
    }

    @Test
    void setManagerFlag_idempotentRepeatOn() {
        Long id = insertVolunteer(true);
        volunteerAdminService.setManagerFlag(id, 1);
        // 重复设同值不报错、值不变
        volunteerAdminService.setManagerFlag(id, 1);
        assertEquals(1, volunteerMapper.selectById(id).getManagerFlag());
    }

    @Test
    void setManagerFlag_nonOneTreatedAsOff() {
        Long id = insertVolunteer(true);
        volunteerAdminService.setManagerFlag(id, 1);
        // 入参非 1（如 null 走 DTO 校验拦不到的兜底）应按取消处理
        volunteerAdminService.setManagerFlag(id, null);
        assertEquals(0, volunteerMapper.selectById(id).getManagerFlag());
    }

    @Test
    void setManagerFlag_guestRejected() {
        Long id = insertVolunteer(false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> volunteerAdminService.setManagerFlag(id, 1), "游客不应可标记为管理团队");
        assertEquals("仅已实名志愿者可标记为管理团队", ex.getMessage());
    }

    @Test
    void setManagerFlag_notExistRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> volunteerAdminService.setManagerFlag(99999999L, 1));
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
