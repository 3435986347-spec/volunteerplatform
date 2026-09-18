package com.hengde.auth;

import com.hengde.auth.constant.NotifyTopic;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.dao.VolunteerNotifyPrefMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.service.NotifyPreferenceService;
import com.hengde.auth.service.SmsNotifyService;
import com.hengde.auth.vo.NotifyPreferenceView;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.sms.SmsNotifyTemplate;
import com.hengde.common.testsupport.RecordingSmsConfig;
import com.hengde.common.testsupport.RecordingSmsConfig.RecordingSmsService;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订阅通知偏好（Row 48，V4 个人中心补全批）：用户关掉的话题在 {@code SmsNotifyService} 统一剔除，
 * 不可关闭的话题存了关闭也照发；每条短信模板恰好归属一个话题。
 *
 * <p><b>需本机 Docker</b>（MySQL）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {
        "hengde.sms.templates.enrollment-approved=T-ENROLL",
        "hengde.sms.templates.reward-punish=T-RP"})
@Import({TestcontainersConfig.class, RecordingSmsConfig.class})
class NotifyPreferenceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 10_000_000L);

    @Autowired
    private NotifyPreferenceService preferenceService;
    @Autowired
    private SmsNotifyService smsNotifyService;
    @Autowired
    private VolunteerNotifyPrefMapper prefMapper;
    @Autowired
    private RecordingSmsService sms;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;

    @BeforeEach
    void reset() {
        sms.clear();
    }

    @Test
    void everyTemplateBelongsToExactlyOneTopic() {
        for (SmsNotifyTemplate t : SmsNotifyTemplate.values()) {
            long owners = java.util.Arrays.stream(NotifyTopic.values()).filter(topic -> topic.getTemplates().contains(t)).count();
            assertEquals(1, owners, "模板 " + t + " 必须恰好归属一个话题——没归类的会被当成不可关闭，归两个的偏好会互相打架");
        }
        Set<SmsNotifyTemplate> covered = EnumSet.noneOf(SmsNotifyTemplate.class);
        for (NotifyTopic topic : NotifyTopic.values()) {
            covered.addAll(topic.getTemplates());
        }
        assertEquals(EnumSet.allOf(SmsNotifyTemplate.class), covered);
        assertFalse(NotifyTopic.REWARD_PUNISH.isOptional(), "奖惩提醒是申诉期的起点，不能让人关掉");
        assertFalse(NotifyTopic.ACTIVITY_CANCELLED.isOptional(), "活动取消不知道就会白跑一趟");
    }

    @Test
    void mine_defaultsToAllOn_andSetOnlyAffectsOptionalTopics() {
        Long v = volunteer();
        assertTrue(preferenceService.mine(v).stream().allMatch(NotifyPreferenceView::smsEnabled), "默认全开");

        preferenceService.set(v, "ENROLLMENT", false);
        preferenceService.set(v, "ENROLLMENT", false);
        preferenceService.set(v, "POINTS", false);
        preferenceService.set(v, "POINTS", true);
        List<NotifyPreferenceView> mine = preferenceService.mine(v);
        assertFalse(find(mine, "ENROLLMENT").smsEnabled());
        assertTrue(find(mine, "POINTS").smsEnabled());

        assertEquals("「奖惩与违规记录」的提醒不能关闭",
                assertThrows(BusinessException.class, () -> preferenceService.set(v, "REWARD_PUNISH", false)).getMessage());
        preferenceService.set(v, "REWARD_PUNISH", true);
        assertEquals("没有这个提醒：NOPE",
                assertThrows(BusinessException.class, () -> preferenceService.set(v, "NOPE", false)).getMessage());
    }

    @Test
    void smsNotify_skipsOptedOutVolunteers_butMandatoryTopicsIgnoreStoredOptOuts() {
        Long off = volunteer();
        Long on = volunteer();
        preferenceService.set(off, "ENROLLMENT", false);

        smsNotifyService.notifyVolunteers(List.of(off, on), SmsNotifyTemplate.ENROLLMENT_APPROVED,
                SmsNotifyTemplate.ENROLLMENT_APPROVED.params("植树", "2026-10-01 09:00", "公园"));
        Set<String> enrollPhones = sms.byTemplateId("T-ENROLL").stream().map(RecordingSmsService.Sent::phone)
                .collect(Collectors.toSet());
        assertEquals(Set.of(phoneOf(on)), enrollPhones, "关掉了报名提醒的人不再收到");

        // 不可关闭的话题：即便库里有一条「关闭」（比如话题归类改过、旧数据留下的），照发
        prefMapper.upsert(off, "REWARD_PUNISH", 0);
        smsNotifyService.notifyVolunteer(off, SmsNotifyTemplate.REWARD_PUNISH,
                SmsNotifyTemplate.REWARD_PUNISH.params("处罚", "违规", "-5"));
        assertEquals(1, sms.byTemplateId("T-RP").size(), "奖惩提醒照发");
    }

    private final java.util.Map<Long, String> phones = new java.util.concurrent.ConcurrentHashMap<>();

    private Long volunteer() {
        String phone = String.format("137%08d", SEQ.incrementAndGet() % 100_000_000L);
        Volunteer v = new Volunteer();
        v.setOpenid("test:pref:" + System.nanoTime() + ":" + SEQ.get());
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setStatus(0);
        volunteerMapper.insert(v);
        phones.put(v.getId(), phone);
        return v.getId();
    }

    private String phoneOf(Long id) {
        return phones.get(id);
    }

    private static NotifyPreferenceView find(List<NotifyPreferenceView> list, String topic) {
        return list.stream().filter(p -> p.topic().equals(topic)).findFirst().orElseThrow();
    }
}
