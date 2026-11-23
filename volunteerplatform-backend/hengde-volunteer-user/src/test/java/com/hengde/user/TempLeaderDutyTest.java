package com.hengde.user;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.user.service.MyProfileService;
import com.hengde.user.vo.MyProfileVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「我的」所属职务（Row 24「如通过活动临时负责人考试则显示活动临时负责人，没有则默认为志愿者」，V4 临时负责人考试批）：
 * 资格按时间现算，到期 / 撤销即变回「志愿者」。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class TempLeaderDutyTest {

    @Autowired
    private MyProfileService myProfileService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void dutyFollowsTheQualification() {
        Long tourist = volunteer(false);
        assertEquals("游客", myProfileService.getMyProfile(tourist).getDuty());

        Long me = volunteer(true);
        MyProfileVO before = myProfileService.getMyProfile(me);
        assertEquals("志愿者", before.getDuty());
        assertFalse(before.getTempLeader());

        LocalDateTime expire = LocalDateTime.now().plusMonths(3).withNano(0);
        jdbc.update("INSERT INTO org_temp_leader_qualification (volunteer_id, source_type, granted_time, expire_time, create_time) "
                + "VALUES (?, 1, NOW(), ?, NOW())", me, expire);
        Long qid = jdbc.queryForObject("SELECT id FROM org_temp_leader_qualification WHERE volunteer_id = ?", Long.class, me);
        MyProfileVO leader = myProfileService.getMyProfile(me);
        assertEquals("活动临时负责人", leader.getDuty());
        assertTrue(leader.getTempLeader());
        assertEquals(expire, leader.getTempLeaderExpireTime());

        jdbc.update("UPDATE org_temp_leader_qualification SET expire_time = ? WHERE id = ?", LocalDateTime.now().minusSeconds(5), qid);
        MyProfileVO expired = myProfileService.getMyProfile(me);
        assertEquals("志愿者", expired.getDuty(), "到期即变回志愿者，不等任何定时任务");
        assertNull(expired.getTempLeaderExpireTime());

        jdbc.update("UPDATE org_temp_leader_qualification SET expire_time = NULL, revoked_by = 1, revoked_time = NOW(), "
                + "revoke_reason = '评价过低' WHERE id = ?", qid);
        assertEquals("志愿者", myProfileService.getMyProfile(me).getDuty(), "撤销即变回志愿者");
    }

    private Long volunteer(boolean registered) {
        Volunteer v = new Volunteer();
        v.setOpenid("test:duty:" + System.nanoTime());
        v.setRealName(registered ? "职务测试" : null);
        v.setStatus(0);
        v.setManagerFlag(0);
        if (registered) {
            v.setRegisterTime(LocalDateTime.now());
        }
        volunteerMapper.insert(v);
        return v.getId();
    }
}
