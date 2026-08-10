package com.hengde.api.config;

import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.entity.VolunteerSanction;
import com.hengde.auth.service.SanctionService;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「拒绝其使用本程序」统一拦截点的行为与放行清单。<b>需本机 Docker。</b>
 *
 * <p><b>为什么这道闸门值得专门测</b>：它是「默认全挡 + 显式放行」，两个方向都会静默出错——
 * 放行清单少一条，被罚得最重的人连申诉都提交不了；多一条（比如图省事写成 {@code /v/honor/**}），
 * 排行榜、勋章、证书就跟着放了出去，而处罚记录上仍白纸黑字写着「拒绝使用本程序」。
 * 两种错误都不会抛异常、不会进日志，只会体现在某个被处罚的人身上。</p>
 *
 * @author hengde
 */
@SpringBootTest(classes = HengdeVolunteerApplication.class)
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class DenyAllUseGateTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private DenyAllUseGate gate;
    @Autowired
    private SanctionService sanctionService;
    @Autowired
    private VolunteerMapper volunteerMapper;

    /**
     * 放行清单的<b>两个方向</b>都钉住：该放的放、该挡的挡。
     *
     * <p>纯函数，不碰数据库。把 {@code EXEMPT_PATHS} 里任一条删掉、或改成 {@code /v/honor/**}，
     * 本用例立刻变红。</p>
     */
    @Test
    void exemptList_opensTheAppealPathAndNothingMore() {
        // 必须放行——理由见 DenyAllUseGate.EXEMPT_PATHS 的逐条说明
        assertTrue(DenyAllUseGate.isExempt("/v/auth/login/wechat"), "挡掉登录，下面几条也就够不着了");
        assertTrue(DenyAllUseGate.isExempt("/v/honor/reward-punishes"), "奖惩记录：他得看得到自己被罚了什么");
        assertTrue(DenyAllUseGate.isExempt("/v/honor/reward-punishes/123/appeal"), "申诉入口（Row 41 F 的 7 天申诉期）");
        assertTrue(DenyAllUseGate.isExempt("/v/honor/sanctions"), "处置查看：只说不能用、不说到几号，是把有期限说成无期限");
        assertTrue(DenyAllUseGate.isExempt("/v/notifications"), "站内提示：告知处罚成立与申诉期限的那条就在这里");
        assertTrue(DenyAllUseGate.isExempt("/v/notifications/9/read"));

        // 必须挡住——这些正是「使用本程序」的部分
        assertFalse(DenyAllUseGate.isExempt("/v/activity/activities"), "浏览/报名活动");
        assertFalse(DenyAllUseGate.isExempt("/v/honor/rankings"), "排行榜；写成 /v/honor/** 就会在这里漏");
        assertFalse(DenyAllUseGate.isExempt("/v/honor/medals"), "勋章；同上");
        assertFalse(DenyAllUseGate.isExempt("/v/honor/certificates"), "证书；同上");
        assertFalse(DenyAllUseGate.isExempt("/v/user/profile"), "改资料");
        assertFalse(DenyAllUseGate.isExempt("/v/organization/groups"), "小组申请——V32 漏掉的正是这一类");
        assertFalse(DenyAllUseGate.isExempt("/v/publicity/notices"), "公示互动；同上");
        assertFalse(DenyAllUseGate.isExempt(null));
    }

    /**
     * 被 {@code ALL} 处置的人：业务路径全挡，放行清单里的照常可用。
     *
     * <p>V32 的缺陷形态就在第一条断言上——那时 {@code ALL} 只被报名/签到两处查到，
     * 小组、管理团队申请、公示互动、改资料全部畅通，「拒绝使用本程序」名不副实。</p>
     */
    @Test
    void deniedAllUse_blocksBusinessPathsButKeepsTheAppealPathOpen() {
        Long vid = insertVolunteer();
        sanctionService.impose(vid, VolunteerSanction.SOURCE_REWARD_PUNISH,
                80_000L + SEQ.incrementAndGet(), SanctionScope.ALL, null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> gate.check("/v/organization/groups", vid),
                "「拒绝使用本程序」必须挡住小组这类入口，否则它只等同于「限制参加活动」");
        assertTrue(ex.getMessage().contains("拒绝使用本程序"), "实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("申诉"), "报错要告诉他还能怎么办，实际：" + ex.getMessage());

        assertThrows(BusinessException.class, () -> gate.check("/v/activity/activities", vid));
        assertThrows(BusinessException.class, () -> gate.check("/v/honor/rankings", vid));
        assertThrows(BusinessException.class, () -> gate.check("/v/user/profile", vid));

        // 申诉链路必须活着：被罚得最重的人不该成为唯一无法申诉的人
        assertDoesNotThrow(() -> gate.check("/v/honor/reward-punishes", vid));
        assertDoesNotThrow(() -> gate.check("/v/honor/reward-punishes/1/appeal", vid));
        assertDoesNotThrow(() -> gate.check("/v/honor/sanctions", vid));
        assertDoesNotThrow(() -> gate.check("/v/notifications", vid));
    }

    /**
     * 只被「限制参加活动」的人，<b>不该</b>被这道闸门挡住。
     *
     * <p>本闸门只管 {@code ALL} 这一档；活动那一档由报名/签到各自那道带锁的闸门负责。
     * 把这里写成「有任何处置就挡」，一条 7 天的活动限制会连改资料、看排行榜一起封掉——
     * 那是把最轻的处罚执行成了最重的。</p>
     */
    @Test
    void activityScopeSanction_isNotThisGatesBusiness() {
        Long vid = insertVolunteer();
        sanctionService.impose(vid, VolunteerSanction.SOURCE_REWARD_PUNISH,
                81_000L + SEQ.incrementAndGet(), SanctionScope.ACTIVITY, 7);

        assertDoesNotThrow(() -> gate.check("/v/user/profile", vid));
        assertDoesNotThrow(() -> gate.check("/v/honor/rankings", vid));
    }

    /** 没有处置的人一路畅通；未登录（id 为 null）不在这里报错，登录校验是上一道的事。 */
    @Test
    void cleanVolunteerAndAnonymousAreNotBlocked() {
        Long vid = insertVolunteer();
        assertDoesNotThrow(() -> gate.check("/v/activity/activities", vid));
        assertDoesNotThrow(() -> gate.check("/v/activity/activities", null));
    }

    private Long insertVolunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_denyall_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        v.setRealName("拒绝使用用例");
        v.setStatus(UserStatus.NORMAL);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }
}
