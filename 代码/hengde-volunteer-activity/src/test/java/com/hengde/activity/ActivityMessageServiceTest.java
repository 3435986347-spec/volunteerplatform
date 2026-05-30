package com.hengde.activity;

import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.service.ActivityMessageService;
import com.hengde.activity.vo.ActivityMessageVO;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动留言（第 3 批·PR1）验证：发表后列表命中且带姓名、下架后不再出现、发到不存在活动被拒、空内容被拒。
 * MySQL + Redis 由 Testcontainers 起（activity 上下文含 Redisson 依赖）。<b>需本机有 Docker。</b>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class ActivityMessageServiceTest {

    @Autowired
    private ActivityMessageService messageService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;

    @Test
    void post_thenList_hasContentAndName() {
        Long aid = insertActivity();
        Long vid = insertVolunteer("留言人甲");
        messageService.post(aid, vid, "活动很棒！");

        List<ActivityMessageVO> records = messageService.list(aid, new PageQuery()).getRecords();
        ActivityMessageVO mine = records.stream().filter(m -> aid.equals(m.getActivityId())).findFirst().orElseThrow();
        assertEquals("活动很棒！", mine.getContent());
        assertEquals("留言人甲", mine.getVolunteerName(), "应带出发表人姓名");
    }

    @Test
    void delete_removesFromList() {
        Long aid = insertActivity();
        Long vid = insertVolunteer("留言人乙");
        Long msgId = messageService.post(aid, vid, "占位留言");

        messageService.delete(msgId);
        List<ActivityMessageVO> records = messageService.list(aid, new PageQuery()).getRecords();
        assertTrue(records.stream().noneMatch(m -> msgId.equals(m.getId())), "下架后不应再出现");
    }

    @Test
    void post_activityNotExist_rejected() {
        Long vid = insertVolunteer("留言人丙");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> messageService.post(99999999L, vid, "对不存在活动的留言"));
        assertTrue(ex.getMessage().contains("活动不存在"));
    }

    @Test
    void post_blankContent_rejected() {
        Long aid = insertActivity();
        Long vid = insertVolunteer("留言人丁");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> messageService.post(aid, vid, "   "));
        assertTrue(ex.getMessage().contains("留言内容不能为空"));
    }

    // ---------- helpers ----------

    private Long insertActivity() {
        Activity a = new Activity();
        a.setTitle("留言活动_" + System.nanoTime());
        a.setStartTime(LocalDateTime.now().minusHours(1));
        a.setEndTime(LocalDateTime.now().plusHours(1));
        a.setStatus(1);
        activityMapper.insert(a);
        a.setSerialNo(a.getId());
        activityMapper.updateById(a);
        return a.getId();
    }

    private Long insertVolunteer(String name) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName(name);
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }
}
