package com.hengde.activity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.dao.PointRecordMapper;
import com.hengde.activity.entity.PointRecord;
import com.hengde.activity.service.PointService;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.biz.dao.VolunteerGroupMapper;
import com.hengde.organization.biz.dto.GroupCreateDTO;
import com.hengde.organization.biz.entity.VolunteerGroup;
import com.hengde.organization.biz.service.GroupService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发起小组扣 50 积分（原型 P23「发起小组（-50积分）」，需方 2026-10-23 拍板补上）。
 *
 * <p>扣分在 organization 的 {@code GroupService} 里发起、经 {@code GroupCreationFee} 接口由 activity 记账——
 * 所以这组用例放在 activity（它的测试上下文里两边都有）。要钉住的：发起即扣、余额不够整笔不建、
 * 驳回如数退回且「已使用」归零、没扣过的旧小组驳回不白送分、扣分与手工扣分 / 兑换共用积分锁。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class GroupCreationFeeTest {

    @Autowired
    private GroupService groupService;
    @Autowired
    private PointService pointService;
    @Autowired
    private PointRecordMapper pointRecordMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private VolunteerGroupMapper groupMapper;
    @Autowired
    private RedissonClient redissonClient;

    @Test
    void creatingAGroupCosts50_andARejectionRefundsIt() {
        Long vid = volunteerWithPoints(60);

        Long groupId = groupService.createForVolunteer(vid, dto("扣分小组"));
        assertEquals(10, pointService.balanceOf(vid), "发起即扣 50");
        assertEquals(50, pointService.summary(vid).getTotalSpent(), "算「已使用」，不冲减累计获得");
        PointRecord charge = pointRecordMapper.selectBySource(PointSourceType.GROUP_CREATE, groupId);
        assertEquals(-50, charge.getChangeAmount());
        assertTrue(charge.getRemark().contains("扣分小组"), charge.getRemark());

        groupService.rejectCreate(groupId, "名称不合适");
        assertEquals(60, pointService.balanceOf(vid), "驳回如数退回");
        assertEquals(0, pointService.summary(vid).getTotalSpent(), "退回之后「已使用」归零");
        assertEquals(60, pointService.summary(vid).getTotalEarned(), "累计获得不被退分抬高");
    }

    @Test
    void notEnoughPoints_noGroupIsCreated_andTheVolunteerCanTryAgainLater() {
        Long vid = volunteerWithPoints(30);

        BusinessException e = assertThrows(BusinessException.class, () -> groupService.createForVolunteer(vid, dto("没钱小组")));
        assertEquals("积分不足：发起小组需要 50 积分，当前 30", e.getMessage());
        assertEquals(0L, groupMapper.selectCount(Wrappers.<VolunteerGroup>lambdaQuery().eq(VolunteerGroup::getLeaderId, vid)),
                "整笔回滚：小组没建、也没占住「一人一组」");
        assertEquals(30, pointService.balanceOf(vid));

        top(vid, 20);
        groupService.createForVolunteer(vid, dto("补够了再发起"));
        assertEquals(0, pointService.balanceOf(vid), "刚好 50 可以发起");
    }

    @Test
    void approvalKeepsTheCharge_andALegacyUnchargedGroupGetsNoRefund() {
        Long vid = volunteerWithPoints(50);
        Long groupId = groupService.createForVolunteer(vid, dto("通过的小组"));
        groupService.approveCreate(groupId, 1L);
        assertEquals(0, pointService.balanceOf(vid), "批准不退");

        // 改造前发起、从没扣过分的待审小组：驳回时不能白送 50
        Long old = volunteerWithPoints(0);
        VolunteerGroup g = new VolunteerGroup();
        g.setGroupNo("G-legacy-" + System.nanoTime());
        g.setName("旧小组");
        g.setLeaderId(old);
        g.setStatus(0);
        groupMapper.insert(g);
        groupService.rejectCreate(g.getId(), null);
        assertEquals(0, pointService.balanceOf(old), "没扣过就不退");
    }

    /**
     * 扣分与手工扣分、商城兑换共用积分锁：测试线程先持住这个人的积分锁，发起小组必须等着；
     * 不取这把锁的话，余额检查与别处的扣减会同时通过，余额被扣成负数。
     */
    @Test
    void chargingWaitsForThePointLock() throws Exception {
        Long vid = volunteerWithPoints(60);
        RLock lock = redissonClient.getLock(PointService.LOCK_KEY_PREFIX + vid);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        lock.lock();
        try {
            Future<Long> create = pool.submit(() -> groupService.createForVolunteer(vid, dto("等锁小组")));
            assertThrows(TimeoutException.class, () -> create.get(1, TimeUnit.SECONDS), "积分锁被占时发起必须等着");
            lock.unlock();
            create.get(15, TimeUnit.SECONDS);
            assertEquals(10, pointService.balanceOf(vid));
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            pool.shutdownNow();
        }
    }

    @Test
    void theRefundPrefixIsReservedForGroupRefunds() {
        Long vid = volunteerWithPoints(0);
        BusinessException e = assertThrows(BusinessException.class, () -> pointService.record(vid, 50,
                PointSourceType.MANUAL, null, PointSourceType.GROUP_REFUND_REQUEST_PREFIX + "1", "占位", 1, 1L));
        assertTrue(e.getMessage().contains("系统保留"), e.getMessage());
        assertEquals(List.of(), pointRecordMapper.selectList(Wrappers.<PointRecord>lambdaQuery()
                .eq(PointRecord::getVolunteerId, vid)));
    }

    // ---------------- helpers ----------------

    private Long volunteerWithPoints(int points) {
        Volunteer v = new Volunteer();
        v.setOpenid("group_fee_" + System.nanoTime());
        v.setRealName("发起人" + System.nanoTime() % 10000);
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        if (points > 0) {
            top(v.getId(), points);
        }
        return v.getId();
    }

    private void top(Long vid, int points) {
        pointService.record(vid, points, PointSourceType.MANUAL, null, "group-fee-" + System.nanoTime(),
                "预置", PointSourceType.OPERATOR_ADMIN, 1L);
    }

    private static GroupCreateDTO dto(String name) {
        GroupCreateDTO d = new GroupCreateDTO();
        d.setName(name + System.nanoTime() % 100000);
        d.setDescription("用例");
        return d;
    }
}
