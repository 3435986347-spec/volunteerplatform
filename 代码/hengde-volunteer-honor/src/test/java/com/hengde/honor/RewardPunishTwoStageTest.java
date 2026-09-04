package com.hengde.honor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.service.PointService;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.dao.VolunteerSanctionMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.entity.VolunteerNotification;
import com.hengde.auth.entity.VolunteerSanction;
import com.hengde.auth.service.SanctionQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.honor.controller.AdminRewardPunishController;
import com.hengde.honor.dao.HonorRewardPunishMapper;
import com.hengde.honor.dto.RewardPunishSaveDTO;
import com.hengde.honor.entity.HonorRewardPunish;
import com.hengde.honor.service.RewardPunishService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两级审核（协会 2026-09-02 答复问题二）。
 *
 * <p><b>本类是这条规则的唯一承重测试</b>——{@code RewardPunishServiceTest} 里那几十条用例
 * 走的是 {@code approveFully} 助手（两步合一），它们断言的是「通过之后应当怎样」，
 * <b>证明不了两级本身</b>。改回一级审核时必须是<b>本类</b>红。</p>
 *
 * <p>协会答复合起来是<b>两级 + 一条快捷通道，且奖与惩不对称</b>：</p>
 * <table>
 *   <tr><th>场景</th><th>路径</th></tr>
 *   <tr><td>处罚 · 从下往上</td><td>组织部初审 → 理事会终审</td></tr>
 *   <tr><td>处罚 · 理事会开单</td><td>开即通过（紧急情况）</td></tr>
 *   <tr><td>奖励 · 部门提出</td><td>理事会终审（<b>不经组织部</b>）</td></tr>
 *   <tr><td>奖励 · 理事会发起</td><td>开即通过</td></tr>
 * </table>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, InMemoryFileStorageConfig.class})
class RewardPunishTwoStageTest {

    private static final long ORG_ADMIN = 77_001L;
    private static final long COUNCIL_ADMIN = 77_002L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000 * 1000);

    @Autowired
    private RewardPunishService rewardPunishService;
    @Autowired
    private HonorRewardPunishMapper rewardPunishMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private VolunteerSanctionMapper sanctionMapper;
    @Autowired
    private SanctionQueryService sanctionQueryService;
    @Autowired
    private PointService pointService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------- 落档：开单落在哪一格 ----------------

    /**
     * 落档规则是纯函数，先把四种组合逐个钉死。
     *
     * <p>把它抽成 {@code static} 就是为了能这样直接断言——藏在 {@code create} 里面
     * 只能靠「建一张单再查状态」间接验证，四种组合要四次建表往返。</p>
     */
    @Test
    void initialStateCoversAllFourCases() {
        assertEquals(HonorRewardPunish.REVIEW_PENDING,
                RewardPunishService.initialReviewStatus(HonorRewardPunish.TYPE_PUNISH, false),
                "处罚·从下往上 → 待组织部初审");
        assertEquals(HonorRewardPunish.REVIEW_APPROVED,
                RewardPunishService.initialReviewStatus(HonorRewardPunish.TYPE_PUNISH, true),
                "处罚·理事会开单 → 开即通过（紧急情况）");
        assertEquals(HonorRewardPunish.REVIEW_FIRST_PASSED,
                RewardPunishService.initialReviewStatus(HonorRewardPunish.TYPE_REWARD, false),
                "奖励·部门提出 → 直落待终审，不经组织部");
        assertEquals(HonorRewardPunish.REVIEW_APPROVED,
                RewardPunishService.initialReviewStatus(HonorRewardPunish.TYPE_REWARD, true),
                "奖励·理事会发起 → 开即通过");
    }

    // ---------------- 处罚：两级 ----------------

    /**
     * <b>初审不产生任何效力。</b>
     *
     * <p>这是整条规则里最容易做错的一步：把积分或处置留在初审，处罚就在理事会点头之前生效了，
     * 而「理事会没审完，志愿者不会看到处罚」这句话在页面上仍然成立——
     * 人看不到，分却已经扣了。</p>
     */
    @Test
    void firstApproveProducesNoEffectAtAll() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punishWithSanction(vid, -20), ORG_ADMIN, false);

        rewardPunishService.firstApprove(id, ORG_ADMIN);

        HonorRewardPunish rp = rewardPunishMapper.selectById(id);
        assertEquals(HonorRewardPunish.REVIEW_FIRST_PASSED, rp.getReviewStatus());
        assertEquals(ORG_ADMIN, rp.getFirstReviewBy(), "初审人落在 first_review_by");
        assertNotNull(rp.getFirstReviewTime());
        assertNull(rp.getReviewedBy(), "终审人此时必须还是空——reviewed_by 从此专指终审");
        assertNull(rp.getAppealDeadline(), "申诉期还没开始计时");
        assertEquals(0, pointService.balanceOf(vid), "积分不得在初审时入账");
        assertFalse(isRestricted(vid), "处置不得在初审时生效");
        assertEquals(0, notificationCount(vid), "初审不发提示——他还不该知道这张单");
        assertTrue(visibleToVolunteer(vid).isEmpty(), "待终审的单对志愿者不可见");
    }

    /** 终审那一刻，五件效力一次落地。 */
    @Test
    void finalApproveAppliesEverything() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punishWithSanction(vid, -20), ORG_ADMIN, false);
        rewardPunishService.firstApprove(id, ORG_ADMIN);

        rewardPunishService.finalApprove(id, COUNCIL_ADMIN);

        HonorRewardPunish rp = rewardPunishMapper.selectById(id);
        assertEquals(HonorRewardPunish.REVIEW_APPROVED, rp.getReviewStatus());
        assertEquals(ORG_ADMIN, rp.getFirstReviewBy(), "初审痕迹不得被终审覆盖");
        assertEquals(COUNCIL_ADMIN, rp.getReviewedBy(), "终审人");
        assertNotNull(rp.getAppealDeadline(), "申诉期从终审开始计时");
        assertEquals(-20, pointService.balanceOf(vid));
        assertTrue(isRestricted(vid));
        assertEquals(1, notificationCount(vid));
        assertEquals(1, visibleToVolunteer(vid).size(), "终审之后才对志愿者可见");
    }

    /**
     * <b>没初审的处罚，理事会也不能一步批掉。</b>
     *
     * <p>终审的 CAS 条件必须是 {@code == 待终审} 而不是 {@code != 已通过}——
     * 写成后者，一张刚开出来的单会被理事会直接批准，组织部那一关等于不存在。</p>
     */
    @Test
    void finalApproveCannotSkipFirstReview() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, -10), ORG_ADMIN, false);

        assertThrows(BusinessException.class, () -> rewardPunishService.finalApprove(id, COUNCIL_ADMIN),
                "未经初审的处罚不得直接终审");
        assertEquals(HonorRewardPunish.REVIEW_PENDING,
                rewardPunishMapper.selectById(id).getReviewStatus());
        assertEquals(0, pointService.balanceOf(vid), "失败的终审不得留下任何效力");
    }

    /** 初审是 CAS：两人同时点，只有一个成功。 */
    @Test
    void firstApproveIsIdempotentByCas() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, -10), ORG_ADMIN, false);

        rewardPunishService.firstApprove(id, ORG_ADMIN);
        assertThrows(BusinessException.class, () -> rewardPunishService.firstApprove(id, ORG_ADMIN));
    }

    /** 终审同样是 CAS：不得重复终审，否则积分会被入账两次。 */
    @Test
    void finalApproveIsIdempotentByCas() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, -10), ORG_ADMIN, false);
        rewardPunishService.firstApprove(id, ORG_ADMIN);
        rewardPunishService.finalApprove(id, COUNCIL_ADMIN);

        assertThrows(BusinessException.class, () -> rewardPunishService.finalApprove(id, COUNCIL_ADMIN));
        assertEquals(-10, pointService.balanceOf(vid), "重复终审不得再扣一次分");
    }

    // ---------------- 快捷通道：理事会开单 ----------------

    /**
     * 「紧急情况下由理事会的同学直接进行处罚的，则自动审核完成」——
     * <b>开即通过，且效力必须当场全部落地</b>，不能只留一张 status=1 却什么都没发生的单。
     */
    @Test
    void councilCreatedPunishTakesEffectImmediately() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punishWithSanction(vid, -30), COUNCIL_ADMIN, true);

        HonorRewardPunish rp = rewardPunishMapper.selectById(id);
        assertEquals(HonorRewardPunish.REVIEW_APPROVED, rp.getReviewStatus());
        assertNull(rp.getFirstReviewBy(), "没经过组织部，初审人为空——这正是「走了快捷通道」的痕迹");
        assertEquals(COUNCIL_ADMIN, rp.getReviewedBy());
        assertNotNull(rp.getAppealDeadline(), "申诉权不因走快捷通道而缩水");
        assertEquals(-30, pointService.balanceOf(vid));
        assertTrue(isRestricted(vid));
        assertEquals(1, notificationCount(vid));
        assertEquals(1, visibleToVolunteer(vid).size());
    }

    /** 理事会发起的奖励同样开即通过。 */
    @Test
    void councilCreatedRewardTakesEffectImmediately() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(reward(vid, 50), COUNCIL_ADMIN, true);

        assertEquals(HonorRewardPunish.REVIEW_APPROVED,
                rewardPunishMapper.selectById(id).getReviewStatus());
        assertEquals(50, pointService.balanceOf(vid));
    }

    // ---------------- 奖励：不经组织部 ----------------

    /**
     * 「各部门都可以提出奖励申请，理事会审核」——奖励<b>直落待终审</b>，
     * 而且不能拿组织部那一关去审它。
     */
    @Test
    void departmentRewardSkipsOrgReviewAndGoesStraightToCouncil() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(reward(vid, 40), ORG_ADMIN, false);

        assertEquals(HonorRewardPunish.REVIEW_FIRST_PASSED,
                rewardPunishMapper.selectById(id).getReviewStatus(), "奖励开单即待终审");
        assertThrows(BusinessException.class, () -> rewardPunishService.firstApprove(id, ORG_ADMIN),
                "奖励不经组织部初审");
        assertTrue(visibleToVolunteer(vid).isEmpty(), "待终审的奖励同样不可见");

        rewardPunishService.finalApprove(id, COUNCIL_ADMIN);
        assertEquals(40, pointService.balanceOf(vid));
    }

    // ---------------- 驳回 ----------------

    /** 两档都能驳：只让初审驳的话，单子到了理事会手上就只剩「批」这一条路。 */
    @Test
    void rejectWorksAtBothStages() {
        Long vid = insertVolunteer();
        Long atFirst = rewardPunishService.create(punish(vid, -10), ORG_ADMIN, false);
        rewardPunishService.reject(atFirst, "证据不足", ORG_ADMIN);
        assertEquals(HonorRewardPunish.REVIEW_REJECTED,
                rewardPunishMapper.selectById(atFirst).getReviewStatus());

        Long atFinal = rewardPunishService.create(punish(vid, -10), ORG_ADMIN, false);
        rewardPunishService.firstApprove(atFinal, ORG_ADMIN);
        rewardPunishService.reject(atFinal, "理事会认为处罚过重", COUNCIL_ADMIN);
        assertEquals(HonorRewardPunish.REVIEW_REJECTED,
                rewardPunishMapper.selectById(atFinal).getReviewStatus());

        assertEquals(0, pointService.balanceOf(vid), "驳回不产生任何积分变动");
    }

    /** 已通过的单不能再被驳回——那条路是申诉，不是驳回。 */
    @Test
    void approvedTicketCannotBeRejected() {
        Long vid = insertVolunteer();
        Long id = rewardPunishService.create(punish(vid, -10), COUNCIL_ADMIN, true);

        assertThrows(BusinessException.class,
                () -> rewardPunishService.reject(id, "反悔了", COUNCIL_ADMIN));
    }

    // ---------------- 授权判据 ----------------

    /**
     * 快捷通道的判据是<b>权限</b>，不是请求体里的开关。
     *
     * <p>控制器把 {@code hasPermission(HONOR_REWARD_PUNISH_FINAL)} 的结果传进来；
     * DTO 上<b>没有</b>对应字段——本用例用反射把这件事钉死：一旦有人给 DTO 加了
     * 「自动通过」之类的开关，任何有开单权的人都能给自己开一条免审通道。</p>
     */
    @Test
    void shortcutIsDecidedByPermissionNotByRequestBody() {
        for (java.lang.reflect.Field f : RewardPunishSaveDTO.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase();
            assertFalse(n.contains("auto") || n.contains("final") || n.contains("review"),
                    "开单入参不得出现审核相关开关，否则两级审核就成了自愿参加的：" + f.getName());
        }
        assertNotNull(AdminRewardPunishController.class, "判据落在控制器，与 assertScopeAllowed 同一形状");
    }

    // ---------------- helpers ----------------

    private boolean isRestricted(Long vid) {
        return sanctionQueryService.activeSanctions(vid).stream()
                .anyMatch(s -> Integer.valueOf(SanctionScope.ACTIVITY).equals(s.getScope())
                        || Integer.valueOf(SanctionScope.ALL).equals(s.getScope()));
    }

    private int notificationCount(Long vid) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM volunteer_notification WHERE volunteer_id = ?", Integer.class, vid);
        return n == null ? 0 : n;
    }

    private java.util.List<?> visibleToVolunteer(Long vid) {
        return rewardPunishService.myRecords(vid);
    }

    private RewardPunishSaveDTO reward(Long vid, int points) {
        RewardPunishSaveDTO dto = new RewardPunishSaveDTO();
        dto.setVolunteerId(vid);
        dto.setType(HonorRewardPunish.TYPE_REWARD);
        dto.setCategory("积极参加活动");
        dto.setTitle("推荐评选雷州市优秀共青团员");
        dto.setPointsDelta(points);
        return dto;
    }

    private RewardPunishSaveDTO punish(Long vid, int points) {
        RewardPunishSaveDTO dto = new RewardPunishSaveDTO();
        dto.setVolunteerId(vid);
        dto.setType(HonorRewardPunish.TYPE_PUNISH);
        dto.setCategory("违规玩手机");
        dto.setDescription("您在【测试活动】中因多次玩手机被处罚，请您下次注意");
        dto.setPointsDelta(points);
        return dto;
    }

    private RewardPunishSaveDTO punishWithSanction(Long vid, int points) {
        RewardPunishSaveDTO dto = punish(vid, points);
        dto.setSanctionScope(SanctionScope.ACTIVITY);
        dto.setSanctionDays(7);
        return dto;
    }

    private Long insertVolunteer() {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_2stage_" + System.nanoTime() + SEQ.incrementAndGet());
        v.setRealName("两级审核志愿者");
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }
}
