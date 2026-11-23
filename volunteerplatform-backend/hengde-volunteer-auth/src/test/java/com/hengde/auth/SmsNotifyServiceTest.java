package com.hengde.auth;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.service.SmsNotifyService;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.sms.SmsNotifyTemplate;
import com.hengde.common.testsupport.RecordingSmsConfig;
import com.hengde.common.testsupport.RecordingSmsConfig.RecordingSmsService;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通知短信发送入口的语义：模板解析、参数校验、号码解密与去重、以及<b>事务提交之后才发</b>。
 *
 * <p>这些行为单独测，是因为它们在每一个业务落点上都一样，而其中两条错了不会有任何征兆：
 * 参数键拼错只会让短信少一块内容，事务回滚后照发则会告诉志愿者一件没有发生的事。</p>
 *
 * <p><b>需本机 Docker。</b></p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {
        // 只配其中一条模板：另一条（活动取消）故意留空，用来验证「没配就跳过、不报错」
        "hengde.sms.templates.enrollment-approved=T-ENROLL-APPROVED"
})
@Import({TestcontainersConfig.class, RecordingSmsConfig.class})
class SmsNotifyServiceTest {

    @Autowired
    private SmsNotifyService smsNotifyService;
    @Autowired
    private RecordingSmsService sms;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        sms.clear();
    }

    @Test
    void sendsWithConfiguredTemplateIdAndDeclaredParamNames() {
        Long vid = insertVolunteer("13900000001");

        smsNotifyService.notifyVolunteer(vid, SmsNotifyTemplate.ENROLLMENT_APPROVED,
                SmsNotifyTemplate.ENROLLMENT_APPROVED.params("植树活动", "2026-09-10 09:00", "西湖公园"));

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-ENROLL-APPROVED");
        assertEquals(1, sent.size());
        assertEquals("13900000001", sent.get(0).phone());
        assertEquals(Map.of("activityName", "植树活动",
                        "startTime", "2026-09-10 09:00",
                        "location", "西湖公园"),
                sent.get(0).params(),
                "参数键必须与火山模板里的占位名逐字一致——拼错不会报错，只会把那一段替换成空");
    }

    @Test
    void templateNotConfigured_skipsWithoutThrowing() {
        Long vid = insertVolunteer("13900000002");

        // 活动取消模板未配 ID：业务不能因此失败，只是这条通知发不出去
        smsNotifyService.notifyVolunteer(vid, SmsNotifyTemplate.ACTIVITY_CANCELLED,
                SmsNotifyTemplate.ACTIVITY_CANCELLED.params("植树活动", "天气原因"));

        assertTrue(sms.all().isEmpty(), "模板没配就不该发，也不该抛");
    }

    @Test
    void volunteerWithoutPhone_skips() {
        Long vid = insertVolunteer(null);   // 游客：没绑手机号

        smsNotifyService.notifyVolunteer(vid, SmsNotifyTemplate.ENROLLMENT_APPROVED,
                SmsNotifyTemplate.ENROLLMENT_APPROVED.params("植树活动", "2026-09-10 09:00", "西湖公园"));

        assertTrue(sms.all().isEmpty(), "没有手机号收不到短信，这不是错误");
    }

    @Test
    void cancelledAccount_isNotDisturbed_butBannedOneStillGetsIt() {
        Long cancelled = insertVolunteer("13900000011", UserStatus.DELETED);
        Long banned = insertVolunteer("13900000012", UserStatus.BANNED);

        smsNotifyService.notifyVolunteers(List.of(cancelled, banned),
                SmsNotifyTemplate.ENROLLMENT_APPROVED,
                SmsNotifyTemplate.ENROLLMENT_APPROVED.params("植树活动", "2026-09-10 09:00", "西湖公园"));

        List<String> phones = sms.byTemplateId("T-ENROLL-APPROVED").stream()
                .map(RecordingSmsService.Sent::phone).toList();
        // 注销 = 用户要求我们别再打扰他；而禁用账号必须照收——协会 2026-08-11 给禁用账号开的
        // 「只能看奖惩、提申诉」那个小口子，起点就是这条通知，挡掉等于把口子又关上。
        assertEquals(List.of("13900000012"), phones);
    }

    @Test
    void emptyPhoneCiphertext_doesNotSinkTheWholeBatch() {
        Long broken = insertVolunteerWithRawPhone("");   // 空串密文（脏数据形态，非 null）
        Long ok = insertVolunteer("13900000013");

        smsNotifyService.notifyVolunteers(List.of(broken, ok),
                SmsNotifyTemplate.ENROLLMENT_APPROVED,
                SmsNotifyTemplate.ENROLLMENT_APPROVED.params("植树活动", "2026-09-10 09:00", "西湖公园"));

        // decrypt("") 会抛（new byte[0 - IV_LENGTH]），而通知的调用方一律 try 住不让它影响业务——
        // 少一道 hasText 守卫，这一条脏数据就会让【整批】通知静默丢失，只留一行 ERROR。
        // 几百人的活动取消通知，一个空字符串就够。
        assertEquals(List.of("13900000013"),
                sms.byTemplateId("T-ENROLL-APPROVED").stream()
                        .map(RecordingSmsService.Sent::phone).toList(),
                "脏数据只应让他自己收不到，不该连累同一批的其他人");
    }

    @Test
    void duplicateRecipients_sendOnlyOnce() {
        Long vid = insertVolunteer("13900000003");

        // 同一个人报了多个场次时，调用方很容易凑出重复 id；重复发同一条短信只会让人以为系统坏了
        smsNotifyService.notifyVolunteers(List.of(vid, vid, vid), SmsNotifyTemplate.ENROLLMENT_APPROVED,
                SmsNotifyTemplate.ENROLLMENT_APPROVED.params("植树活动", "2026-09-10 09:00", "西湖公园"));

        assertEquals(1, sms.byTemplateId("T-ENROLL-APPROVED").size());
    }

    @Test
    void wrongParamKeys_throwImmediately() {
        Long vid = insertVolunteer("13900000004");

        // 手工拼 Map 且键写错：这是编码错误，必须当场炸，不能变成一条内容缺一块的短信
        assertThrows(IllegalArgumentException.class,
                () -> smsNotifyService.notifyVolunteer(vid, SmsNotifyTemplate.ENROLLMENT_APPROVED,
                        Map.of("activityname", "植树活动", "startTime", "x", "location", "y")));
        assertTrue(sms.all().isEmpty());
    }

    @Test
    void paramCountMismatch_throwsFromTemplate() {
        assertThrows(IllegalArgumentException.class,
                () -> SmsNotifyTemplate.ENROLLMENT_APPROVED.params("只给一个"));
    }

    @Test
    void insideTransaction_sendsOnlyAfterCommit() {
        Long vid = insertVolunteer("13900000005");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            smsNotifyService.notifyVolunteer(vid, SmsNotifyTemplate.ENROLLMENT_APPROVED,
                    SmsNotifyTemplate.ENROLLMENT_APPROVED.params("植树活动", "2026-09-10 09:00", "西湖公园"));
            assertTrue(sms.all().isEmpty(), "事务还没提交，短信不该已经发出去");
        });

        assertEquals(1, sms.byTemplateId("T-ENROLL-APPROVED").size(), "提交之后才发");
    }

    @Test
    void rollback_sendsNothing() {
        Long vid = insertVolunteer("13900000006");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            smsNotifyService.notifyVolunteer(vid, SmsNotifyTemplate.ENROLLMENT_APPROVED,
                    SmsNotifyTemplate.ENROLLMENT_APPROVED.params("植树活动", "2026-09-10 09:00", "西湖公园"));
            throw new IllegalStateException("业务在通知之后失败了");
        }));

        // 这一条是整套设计的理由：短信撤不回，而业务还会回滚。
        // 若在事务内直接发，志愿者手里会躺着一条「您的报名已通过审核」，而库里什么都没变。
        assertTrue(sms.all().isEmpty(), "事务回滚了就一条都不该发");
    }

    private Long insertVolunteer(String phonePlain) {
        return insertVolunteer(phonePlain, UserStatus.NORMAL);
    }

    /** 直接写入未加密的原始值，用来造「密文是空串」这类脏数据。 */
    private Long insertVolunteerWithRawPhone(String rawPhoneColumn) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName("脏数据收件人");
        v.setPhone(rawPhoneColumn);
        v.setStatus(UserStatus.NORMAL);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Long insertVolunteer(String phonePlain, Integer status) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime());
        v.setRealName("短信收件人");
        if (phonePlain != null) {
            v.setPhone(cryptoUtil.encrypt(phonePlain));
            v.setPhoneHash(cryptoUtil.hashPhone(phonePlain));
        }
        v.setStatus(status);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }
}
