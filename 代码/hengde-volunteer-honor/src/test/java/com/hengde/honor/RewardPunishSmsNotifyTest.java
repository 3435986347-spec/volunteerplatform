package com.hengde.honor;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.testsupport.RecordingSmsConfig;
import com.hengde.common.testsupport.RecordingSmsConfig.RecordingSmsService;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.honor.dto.RewardPunishSaveDTO;
import com.hengde.honor.entity.HonorRewardPunish;
import com.hengde.honor.service.RewardPunishService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 奖惩审核通过的短信提示（xlsx Row 41 F「审核之后，志愿者会收到提示」）。
 *
 * <p>此前这句需求只落了<b>站内</b>一半，志愿者要主动打开小程序才看得见——
 * 而这条提示是 7 天申诉期的起点。本类钉住短信这一半：只在<b>审核通过</b>时发、内容分得清奖与惩。</p>
 *
 * <p>同时钉住一条<b>已知的缺口</b>：协会报备的这条模板没有放申诉截止日期的位置。
 * 用例把它写成断言，是为了让「哪天协会补了带日期的模板」这件事有个明确的落点，
 * 而不是几个月后才有人想起来短信里少了什么。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = "hengde.sms.templates.reward-punish=T-REWARD-PUNISH")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class,
        InMemoryFileStorageConfig.class, RecordingSmsConfig.class})
class RewardPunishSmsNotifyTest {

    private static final long ADMIN = 901L;

    @Autowired
    private RewardPunishService rewardPunishService;
    @Autowired
    private com.hengde.honor.dao.HonorRewardPunishMapper rewardPunishMapper;
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
    void pendingReview_sendsNothing() {
        Long vid = insertVolunteer("13933330001");
        rewardPunishService.create(punish(vid, "活动期间玩手机", -20), ADMIN, false);

        assertTrue(sms.all().isEmpty(),
                "Row 41 F 是「审核之后才可显示」——待审核期间它对志愿者根本不存在，更不该发短信");
    }

    @Test
    void approvedPunish_sendsWithTypeTitleAndPoints() {
        Long vid = insertVolunteer("13933330002");
        Long id = rewardPunishService.create(punish(vid, "活动期间玩手机", -20), ADMIN, false);
        sms.clear();

        approveFully(id, ADMIN);

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-REWARD-PUNISH");
        assertEquals(1, sent.size());
        assertEquals("13933330002", sent.get(0).phone());
        assertEquals("处罚", sent.get(0).params().get("type"));
        assertEquals("活动期间玩手机", sent.get(0).params().get("title"));
        assertEquals("-20", sent.get(0).params().get("points"));
    }

    @Test
    void approvedReward_saysRewardNotPunish() {
        Long vid = insertVolunteer("13933330003");
        RewardPunishSaveDTO dto = new RewardPunishSaveDTO();
        dto.setVolunteerId(vid);
        dto.setType(HonorRewardPunish.TYPE_REWARD);
        dto.setCategory("积极参加活动");
        dto.setTitle("推荐评选优秀志愿者");
        dto.setPointsDelta(200);
        Long id = rewardPunishService.create(dto, ADMIN, false);
        sms.clear();

        approveFully(id, ADMIN);

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-REWARD-PUNISH");
        assertEquals(1, sent.size());
        assertEquals("奖励", sent.get(0).params().get("type"), "奖和惩必须在短信里分得清");
        assertEquals("200", sent.get(0).params().get("points"));
    }

    @Test
    void approvedPunish_smsCannotCarryAppealDeadline_knownGap() {
        Long vid = insertVolunteer("13933330004");
        Long id = rewardPunishService.create(punish(vid, "早退", 0), ADMIN, false);
        sms.clear();

        approveFully(id, ADMIN);

        // 【这是缺口不是行为】协会报备的模板正文只有 type/title/points 三个占位，
        // 没有地方写「几号之前可以申诉」。而 Row 41 F 把「收到提示」和「7 天申诉期」写在同一句话里。
        // 站内提示写了准确到秒的截止时刻，短信只能引导他去小程序看。
        // 协会补一条带 deadline 的模板之后，这条断言应当反过来。
        assertEquals(java.util.Set.of("type", "title", "points"),
                sms.byTemplateId("T-REWARD-PUNISH").get(0).params().keySet(),
                "模板变量一旦变化（协会补报了带申诉截止日期的版本），这条会红——那正是要改代码的信号");
    }

    private RewardPunishSaveDTO punish(Long volunteerId, String title, int pointsDelta) {
        RewardPunishSaveDTO dto = new RewardPunishSaveDTO();
        dto.setVolunteerId(volunteerId);
        dto.setType(HonorRewardPunish.TYPE_PUNISH);
        dto.setCategory("活动违规");
        dto.setTitle(title);
        dto.setDescription("用例造的奖惩单");
        dto.setPointsDelta(pointsDelta);
        return dto;
    }

    private Long insertVolunteer(String phonePlain) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_rpsms_" + System.nanoTime());
        v.setRealName("奖惩短信收件人");
        v.setPhone(cryptoUtil.encrypt(phonePlain));
        v.setPhoneHash(cryptoUtil.hashPhone(phonePlain));
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    /**
     * 推到「已通过」。两级审核改造后效力全在<b>终审</b>那一刻，
     * 本类断言的是「通过之后发了什么短信」，故把两步合起来。
     * 两级审核本身由 {@link RewardPunishTwoStageTest} 钉住。
     */
    private void approveFully(Long id, Long adminId) {
        // 只有处罚才从「待初审」起步；奖励开单即落「待终审」（不经组织部），
        // 对它调 firstApprove 会正确地报「已初审」——那是新模型在起作用，不是用例写错了。
        if (Integer.valueOf(HonorRewardPunish.REVIEW_PENDING)
                .equals(rewardPunishMapper.selectById(id).getReviewStatus())) {
            rewardPunishService.firstApprove(id, adminId);
        }
        rewardPunishService.finalApprove(id, adminId);
    }

}
