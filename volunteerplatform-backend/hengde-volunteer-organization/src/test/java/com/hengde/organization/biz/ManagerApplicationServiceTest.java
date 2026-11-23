package com.hengde.organization.biz;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.ManagerApplicationMapper;
import com.hengde.organization.biz.dto.ManagerApplyDTO;
import com.hengde.organization.biz.entity.ManagerApplication;
import com.hengde.organization.biz.service.ManagerApplicationService;
import com.hengde.organization.biz.vo.ManagerApplicationVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报名管理团队（V23）：申请/审核链 + 账号状态复核 + CAS。MySQL + Redis 由 Testcontainers 起（apply 用 Redisson 锁）。
 * <b>需本机有 Docker。</b> 数据跨方法累积，各用例用独立志愿者隔离。
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class ManagerApplicationServiceTest {

    private static final long ADMIN = 100L;
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private ManagerApplicationService service;
    @Autowired
    private ManagerApplicationMapper applicationMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;

    @Test
    void apply_landsPending() {
        Long vid = insertVolunteer(0, true, 0);
        Long id = service.apply(vid, dto("想加入组织部"));
        ManagerApplication a = applicationMapper.selectById(id);
        assertEquals(0, a.getStatus().intValue(), "申请落待审");
        assertEquals(vid, a.getVolunteerId());
    }

    @Test
    void apply_guestRejected() {
        Long vid = insertVolunteer(0, false, 0); // 未实名
        BusinessException ex = assertThrows(BusinessException.class, () -> service.apply(vid, dto("x")));
        assertTrue(ex.getMessage().contains("实名"), "游客须先实名");
    }

    @Test
    void apply_disabledRejected() {
        Long vid = insertVolunteer(1, true, 0); // 禁用但已实名
        BusinessException ex = assertThrows(BusinessException.class, () -> service.apply(vid, dto("x")));
        assertTrue(ex.getMessage().contains("账号状态"), "禁用账号不可申请");
    }

    @Test
    void apply_duplicatePendingRejected() {
        Long vid = insertVolunteer(0, true, 0);
        service.apply(vid, dto("first"));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.apply(vid, dto("second")));
        assertTrue(ex.getMessage().contains("待审核"), "已有待审则拒重复提交");
    }

    @Test
    void apply_alreadyManagerRejected() {
        Long vid = insertVolunteer(0, true, 1); // 已是管理团队
        BusinessException ex = assertThrows(BusinessException.class, () -> service.apply(vid, dto("x")));
        assertTrue(ex.getMessage().contains("管理团队"), "已是管理团队无需申请");
    }

    @Test
    void approve_setsManagerFlagAndApproved() {
        Long vid = insertVolunteer(0, true, 0);
        Long id = service.apply(vid, dto("x"));
        service.approve(id, ADMIN);
        assertEquals(1, applicationMapper.selectById(id).getStatus().intValue(), "通过后 status=1");
        assertEquals(Integer.valueOf(1), volunteerMapper.selectById(vid).getManagerFlag(), "通过即置 manager_flag=1");
    }

    @Test
    void approve_disabledDuringPending_rejectedAndStaysPending() {
        Long vid = insertVolunteer(0, true, 0);
        Long id = service.apply(vid, dto("x"));
        // 待审期间被禁用
        Volunteer v = volunteerMapper.selectById(vid);
        v.setStatus(1);
        volunteerMapper.updateById(v);

        assertThrows(BusinessException.class, () -> service.approve(id, ADMIN));
        assertEquals(0, applicationMapper.selectById(id).getStatus().intValue(), "审批失败申请仍待审");
        Integer flag = volunteerMapper.selectById(vid).getManagerFlag();
        assertTrue(flag == null || flag == 0, "未通过则不应置 manager_flag");
    }

    @Test
    void reject_recordsReason() {
        Long vid = insertVolunteer(0, true, 0);
        Long id = service.apply(vid, dto("x"));
        service.reject(id, "材料不全", ADMIN);
        ManagerApplication a = applicationMapper.selectById(id);
        assertEquals(2, a.getStatus().intValue());
        assertEquals("材料不全", a.getRejectReason());
    }

    @Test
    void approve_repeatRejectedByCas() {
        Long vid = insertVolunteer(0, true, 0);
        Long id = service.apply(vid, dto("x"));
        service.approve(id, ADMIN);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.approve(id, ADMIN));
        assertTrue(ex.getMessage().contains("待审核"), "对非待审再审核被 CAS 拦下");
        assertEquals(1, applicationMapper.selectById(id).getStatus().intValue(), "状态保持已通过");
        assertEquals(Integer.valueOf(1), volunteerMapper.selectById(vid).getManagerFlag(), "标记保持，不被回滚成错误态");
    }

    @Test
    void myApplication_returnsLatest() {
        Long vid = insertVolunteer(0, true, 0);
        service.apply(vid, dto("only"));
        ManagerApplicationVO vo = service.myApplication(vid);
        assertNotNull(vo);
        assertEquals(0, vo.getStatus().intValue());
    }

    @Test
    void list_defaultPendingWithName() {
        Long vid = insertVolunteer(0, true, 0);
        service.apply(vid, dto("x"));
        PageResult<ManagerApplicationVO> page = service.list(new PageQuery(), null);
        ManagerApplicationVO row = page.getRecords().stream()
                .filter(r -> r.getVolunteerId().equals(vid)).findFirst().orElseThrow();
        assertEquals("测试志愿者", row.getVolunteerName(), "后台列表带申请人姓名");
        assertEquals(0, row.getStatus().intValue());
    }

    // ---------- helpers ----------

    private ManagerApplyDTO dto(String reason) {
        ManagerApplyDTO d = new ManagerApplyDTO();
        d.setReason(reason);
        return d;
    }

    private Long insertVolunteer(int status, boolean registered, int managerFlag) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:mgrapp:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setRealName("测试志愿者");
        v.setStatus(status);
        v.setManagerFlag(managerFlag);
        if (registered) {
            v.setRegisterTime(LocalDateTime.now());
        }
        volunteerMapper.insert(v);
        return v.getId();
    }
}
