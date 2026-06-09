package com.hengde.data;

import com.hengde.activity.constant.ActivityStatus;
import com.hengde.activity.constant.SecretaryStatus;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.data.service.DashboardService;
import com.hengde.data.vo.DashboardVO;
import com.hengde.organization.biz.dao.VolunteerSquadMapper;
import com.hengde.organization.biz.entity.VolunteerSquad;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 数据看板聚合验证。<b>需本机 Docker</b>（MySQL + Redis——经活动域传递引入 redisson starter）。
 *
 * <p>看板统计是<b>全表聚合</b>，故断言「插入前后的增量」而非绝对值（避免受 Flyway 种子/跨用例数据干扰）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class DashboardServiceTest {

    private static final AtomicLong SEQ = new AtomicLong();

    private DashboardService dashboardService;
    private VolunteerMapper volunteerMapper;
    private VolunteerSquadMapper squadMapper;
    private ActivityMapper activityMapper;
    private ActivityAttendanceMapper attendanceMapper;

    @Autowired
    public void setDashboardService(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Autowired
    public void setSquadMapper(VolunteerSquadMapper squadMapper) {
        this.squadMapper = squadMapper;
    }

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setAttendanceMapper(ActivityAttendanceMapper attendanceMapper) {
        this.attendanceMapper = attendanceMapper;
    }

    @Test
    void overviewReflectsInsertedDataByDelta() {
        DashboardVO before = dashboardService.overview();

        insertVolunteer(true, 0, 0);              // 已实名、非管理团队、正常
        insertVolunteer(false, 0, 0);             // 游客（register_time 空）——不计入注册数
        insertVolunteer(true, 1, 0);              // 已实名 + 管理团队 + 正常 → 计入管理团队
        insertVolunteer(true, 1, 1);              // 已实名管理团队但「禁用」→ 不计管理团队（但算注册）
        insertVolunteer(false, 1, 0);             // 游客 + 管理团队标记 → 既不计管理团队也不计注册
        insertSquad();
        Long a1 = insertActivity(ActivityStatus.PUBLISHED);
        insertActivity(ActivityStatus.FINISHED);
        Long a3 = insertActivity(ActivityStatus.DRAFT); // 草稿不计入场次，其上的签到/时长也不计
        // 考勤用独立游客承载（不影响注册/管理团队计数），且保证 (activity_id, volunteer_id) 满足 uk_activity_volunteer 唯一约束
        insertAttendance(insertVolunteer(false, 0, 0), a1, true, 90, SecretaryStatus.CONFIRMED); // 参与 + 已确认 90 分钟
        insertAttendance(insertVolunteer(false, 0, 0), a1, true, 60, SecretaryStatus.PENDING);   // 参与，但时长未确认不计入分钟
        insertAttendance(insertVolunteer(false, 0, 0), a1, false, 0, SecretaryStatus.PENDING);   // 无签到 → 不计参与人次
        insertAttendance(insertVolunteer(false, 0, 0), a3, true, 30, SecretaryStatus.CONFIRMED); // 草稿活动签到——参与/时长均不计（活动状态守卫）

        DashboardVO after = dashboardService.overview();

        assertEquals(3L, after.getRegisteredVolunteers() - before.getRegisteredVolunteers(),
                "注册数 +3（两名游客不计）");
        assertEquals(1L, after.getManagerCount() - before.getManagerCount(),
                "管理团队 +1（禁用 manager、游客 manager 均不计）");
        assertEquals(2L, after.getActivityCount() - before.getActivityCount(),
                "场次 +2（草稿不计）");
        assertEquals(2L, after.getParticipationCount() - before.getParticipationCount(),
                "参与人次 +2（无签到、草稿活动签到均不计）");
        assertEquals(90L, after.getTotalServiceMinutes() - before.getTotalServiceMinutes(),
                "总时长仅累计已发布/已结束活动上秘书已确认的（+90，未确认 60、草稿 30 均不计）");
        assertEquals(1L, after.getSquadCount() - before.getSquadCount(), "分队 +1");
    }

    // ---------- helpers ----------

    private Long insertVolunteer(boolean registered, int managerFlag, int status) {
        Volunteer v = new Volunteer();
        v.setOpenid("dash:" + System.nanoTime() + ":" + SEQ.incrementAndGet());
        v.setStatus(status);
        v.setManagerFlag(managerFlag);
        if (registered) {
            v.setRealName("看板测试");
            v.setRegisterTime(LocalDateTime.now());
        }
        volunteerMapper.insert(v);
        return v.getId();
    }

    private void insertSquad() {
        VolunteerSquad s = new VolunteerSquad();
        s.setName("看板分队_" + System.nanoTime());
        s.setType("学校");
        s.setStatus(1);
        squadMapper.insert(s);
    }

    private Long insertActivity(int status) {
        Activity a = new Activity();
        a.setTitle("看板活动_" + System.nanoTime());
        a.setStartTime(LocalDateTime.now());
        a.setEndTime(LocalDateTime.now().plusHours(2));
        a.setStatus(status);
        activityMapper.insert(a);
        return a.getId();
    }

    private void insertAttendance(Long volunteerId, Long activityId, boolean hasCheckIn, int minutes, int secretaryStatus) {
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(activityId);
        att.setVolunteerId(volunteerId);
        if (hasCheckIn) {
            att.setCheckInTime(LocalDateTime.now());
        }
        att.setServiceMinutes(minutes);
        att.setSecretaryStatus(secretaryStatus);
        attendanceMapper.insert(att);
    }
}
