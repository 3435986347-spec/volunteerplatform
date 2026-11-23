package com.hengde.honor;

import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.honor.dao.HonorCertificateMapper;
import com.hengde.honor.entity.HonorCertificate;
import com.hengde.honor.service.CertificateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 证书编号碰撞后的<b>换号重试</b>。
 *
 * <p><b>为什么单开一个类</b>：这里要 {@code @MockitoSpyBean} 换掉证书 Mapper，
 * 那会让本类拿到独立的 Spring 上下文；放进 {@code CertificateServiceTest} 会把它
 * 与其余 honor 用例共享的上下文一并拆开，白白多花一次启动。</p>
 *
 * <p><b>为什么非要造这个场景</b>：编号是「秒级时间戳 + 6 位随机」，同一秒内只有 10^6 个取值。
 * 单次交互路径上撞号确实是异常（抛出去、用户重试一次即可），但<b>批量补发把这个前提翻了过来</b>：
 * 一次上千张会把大量证书挤进同一秒，碰撞概率 ≈ 1 − exp(−k²/2·10^6)（k = 该秒内张数），
 * 千张量级下一次跑撞上一次属于常态。而补偿扫描是逐条 catch 的，
 * 原先「原样抛出」在那条路径上会被吞成一行日志：<b>那个人静默无证，接口却报补完了</b>——
 * 与「并发软删仍报上传成功」是同一形状。</p>
 *
 * <p>真实碰撞是 10^6 分之一的事件，靠跑量去撞只会得到偶发用例，故从 Mapper 这一层
 * <b>确定性地</b>造出 {@code uk_cert_no} 冲突。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, InMemoryFileStorageConfig.class})
class CertificateCertNoCollisionTest {

    @MockitoSpyBean
    private HonorCertificateMapper certificateMapper;

    @Autowired
    private CertificateService certificateService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper slotMapper;
    @Autowired
    private ActivityAttendanceMapper attendanceMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private org.mybatis.spring.SqlSessionTemplate sqlSessionTemplate;

    /** 撞号两次后换号成功；<b>不得把那个人漏掉</b>。 */
    @Test
    void createForSlot_onCertNoCollision_retriesWithANewNumber() {
        Fixture f = confirmedFixture();
        AtomicInteger inserts = new AtomicInteger();
        doAnswer(inv -> {
            if (inserts.getAndIncrement() < 2) {
                // 模拟撞 uk_cert_no：此时按 (type, volunteerId, slotId) 查不到赢家，
                // 正是原先直接 rethrow、把这个人静默丢掉的那条分支
                throw new DuplicateKeyException("Duplicate entry 'x' for key 'uk_cert_no'");
            }
            // 【不能用 inv.callRealMethod()】被 spy 的是 MyBatis 的接口代理，接口上没有「真实方法」，
            // Mockito 会直接报错。改从 SqlSession 现取一个未被替换的 Mapper 代理来落库。
            return sqlSessionTemplate.getMapper(HonorCertificateMapper.class)
                    .insert(inv.getArgument(0, HonorCertificate.class));
        }).when(certificateMapper).insert(any(HonorCertificate.class));

        Long certId = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);

        assertNotNull(certId, "撞号是瞬时的，换个号就该成功——不能让这个人没有证书");
        assertEquals(3, inserts.get(), "前两次撞号、第三次成功");
        HonorCertificate cert = certificateMapper.selectById(certId);
        assertNotNull(cert.getCertNo());
        assertEquals(f.slotId, cert.getSlotId());
    }

    /** 一直撞就<b>大声失败</b>——重试有上限，不能无限循环，更不能返回一个不存在的 id。 */
    @Test
    void createForSlot_whenCollisionsNeverStop_failsLoudlyInsteadOfLooping() {
        Fixture f = confirmedFixture();
        AtomicInteger inserts = new AtomicInteger();
        doAnswer(inv -> {
            inserts.incrementAndGet();
            throw new DuplicateKeyException("Duplicate entry 'x' for key 'uk_cert_no'");
        }).when(certificateMapper).insert(any(HonorCertificate.class));

        assertThrows(DuplicateKeyException.class,
                () -> certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId));
        assertEquals(5, inserts.get(), "重试次数必须有界（当前 5 次），否则一条坏数据能把补发任务卡死");
    }

    // ---------- 夹具 ----------

    private record Fixture(Long activityId, Long slotId, Long volunteerId) {
    }

    private Fixture confirmedFixture() {
        Activity a = new Activity();
        a.setTitle("撞号测试活动_" + System.nanoTime());
        a.setStartTime(LocalDateTime.now().minusHours(3));
        a.setEndTime(LocalDateTime.now().minusHours(1));
        a.setStatus(1);
        a.setRunStatus(2);
        a.setNeedAudit(0);
        a.setMinProjects(0);
        a.setRequireMinJoinCount(0);
        a.setPointsBase(100);
        a.setLeaderMultiplier(new BigDecimal("1.4"));
        a.setManagerMultiplier(new BigDecimal("1.2"));
        activityMapper.insert(a);
        a.setSerialNo(a.getId());
        activityMapper.updateById(a);

        ActivitySlot s = new ActivitySlot();
        s.setActivityId(a.getId());
        s.setProjectName("岗位_" + System.nanoTime());
        s.setStartTime(LocalDateTime.now().minusHours(3));
        s.setEndTime(LocalDateTime.now().minusHours(1));
        s.setNeedCount(10);
        slotMapper.insert(s);

        Volunteer v = new Volunteer();
        v.setOpenid("openid_collision_" + System.nanoTime());
        v.setRealName("撞号志愿者");
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);

        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(a.getId());
        att.setSlotId(s.getId());
        att.setVolunteerId(v.getId());
        att.setCheckInTime(LocalDateTime.now().minusHours(3));
        att.setCheckOutTime(LocalDateTime.now().minusHours(1));
        att.setServiceMinutes(120);
        att.setAttendStatus(1);
        att.setSecretaryStatus(1);
        att.setSecretaryTime(LocalDateTime.now());
        att.setPointsStatus(0);
        att.setPointsFactor(0);
        attendanceMapper.insert(att);

        return new Fixture(a.getId(), s.getId(), v.getId());
    }
}
