package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.service.CrowdfundService;
import com.hengde.donate.service.PairProjectService;
import com.hengde.donate.service.PairService;
import com.hengde.donate.vo.PairVOs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.next;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 助学助困结对与项目众筹（V3 结对批）：全流程与每一条拒绝条件。
 *
 * <p><b>本批不碰支付</b>，所以这里断言的「金额」全是<b>认捐额</b>：它在「确认结对成立」那一刻才累加，
 * 取消已成立的结对会退回去。项目状态与认捐额必须始终对得上——只看返回值的用例看不出这一点。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class PairServiceTest {

    @Autowired
    private PairProjectService projectService;
    @Autowired
    private PairService pairService;
    @Autowired
    private CrowdfundService crowdfundService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void projectLifecycle_draftIsInvisible_endedCannotBeEdited() {
        Long id = projectService.create(project("结对生命周期-" + next(), "1000"), ADMIN);
        Long viewer = volunteer("看项目的人");

        assertEquals("项目不存在", assertThrows(BusinessException.class,
                () -> projectService.detailForVolunteer(id, viewer)).getMessage(), "草稿对志愿者等于不存在");
        assertTrue(assertThrows(BusinessException.class, () -> projectService.end(id))
                .getMessage().contains("只有进行中"), "草稿不能直接结束");

        projectService.publish(id);
        PairVOs.Project vo = projectService.detailForVolunteer(id, viewer);
        assertEquals(PairFlow.PROJECT_OPEN, vo.getStatus());
        assertEquals(0, vo.getParticipantCount());
        assertEquals(0, vo.getProgressPercent());
        assertTrue(vo.getCanRegister());
        assertNull(vo.getMyPair());

        projectService.update(id, project("改个名字-" + next(), "1200"));
        assertEquals(new BigDecimal("1200.00"), projectService.detailForAdmin(id).getTargetAmount());

        projectService.end(id);
        assertTrue(assertThrows(BusinessException.class,
                () -> projectService.update(id, project("结束后再改", "1300"))).getMessage().contains("已结束"));
        assertTrue(assertThrows(BusinessException.class, () -> projectService.delete(id))
                .getMessage().contains("只有草稿"), "上过架的项目只能结束、不能删");
    }

    @Test
    void pledgedAmount_movesOnlyWhenEstablished_andProjectFlipsToPairedWhenFull() {
        Long id = projectService.create(project("认捐记账-" + next(), "1000"), ADMIN);
        projectService.publish(id);
        Long a = volunteer("结对甲");
        Long b = volunteer("结对乙");
        Long c = volunteer("结对丙");

        PairVOs.PairRecord ra = pairService.register(id, a, register(PairFlow.AMOUNT_PARTIAL, "600", "我来一部分"));
        assertEquals(PairFlow.PAIR_REGISTERED, ra.getStatus());
        assertEquals(BigDecimal.ZERO.setScale(2), projectService.detailForAdmin(id).getPledgedAmount(),
                "只登记还没确认时，认捐额不动——它记的是已成立的结对");
        assertEquals(1, projectService.detailForAdmin(id).getParticipantCount(), "参加人数含待确认的");

        pairService.establish(ra.getId(), ADMIN);
        assertEquals(new BigDecimal("600.00"), projectService.detailForAdmin(id).getPledgedAmount());
        assertEquals(60, projectService.detailForAdmin(id).getProgressPercent());
        assertEquals(PairFlow.PROJECT_OPEN, projectService.detailForAdmin(id).getStatus());

        // 全款＝按当前缺口算
        PairVOs.PairRecord rb = pairService.register(id, b, register(PairFlow.AMOUNT_FULL, null, null));
        assertEquals(new BigDecimal("400.00"), rb.getAmount());
        pairService.establish(rb.getId(), ADMIN);
        PairVOs.Project full = projectService.detailForAdmin(id);
        assertEquals(new BigDecimal("1000.00"), full.getPledgedAmount());
        assertEquals(100, full.getProgressPercent());
        assertEquals(PairFlow.PROJECT_PAIRED, full.getStatus(), "认捐额达标即「结对成功」");

        assertTrue(assertThrows(BusinessException.class,
                () -> pairService.register(id, c, register(PairFlow.AMOUNT_FULL, null, null)))
                .getMessage().contains("不接受结对登记"), "已结对的项目不再接受登记");

        // 取消已成立的：认捐额退回、项目回到进行中，别人又能登记了
        pairService.cancel(rb.getId(), "结对人联系不上", ADMIN);
        PairVOs.Project reopened = projectService.detailForAdmin(id);
        assertEquals(new BigDecimal("600.00"), reopened.getPledgedAmount());
        assertEquals(PairFlow.PROJECT_OPEN, reopened.getStatus());
        assertNotNull(pairService.register(id, c, register(PairFlow.AMOUNT_PARTIAL, "100", null)));
    }

    @Test
    void registerRules_realNameOnly_oneActivePerProject_andAmountWithinGap() {
        Long id = projectService.create(project("登记规则-" + next(), "500"), ADMIN);
        projectService.publish(id);
        Long guest = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "游客", phone(), false);
        Long a = volunteer("规则甲");

        assertTrue(assertThrows(BusinessException.class,
                () -> pairService.register(id, guest, register(PairFlow.AMOUNT_PARTIAL, "100", null)))
                .getMessage().contains("实名"), "未实名不能登记");
        assertTrue(assertThrows(BusinessException.class,
                () -> pairService.register(id, a, register(PairFlow.AMOUNT_PARTIAL, "600", null)))
                .getMessage().contains("剩余缺口"), "认捐额不能超过缺口");
        assertTrue(assertThrows(BusinessException.class,
                () -> pairService.register(id, a, register(PairFlow.AMOUNT_PARTIAL, null, null)))
                .getMessage().contains("请填写认捐金额"));

        PairVOs.PairRecord r = pairService.register(id, a, register(PairFlow.AMOUNT_PARTIAL, "100", null));
        assertTrue(assertThrows(BusinessException.class,
                () -> pairService.register(id, a, register(PairFlow.AMOUNT_PARTIAL, "100", null)))
                .getMessage().contains("已经登记过"), "一人一项目只能有一条活登记");

        // 撤回之后占位释放，可以重新登记
        pairService.withdraw(id, a);
        assertEquals(PairFlow.PAIR_CANCELLED, statusOf(r.getId()));
        PairVOs.PairRecord again = pairService.register(id, a, register(PairFlow.AMOUNT_PARTIAL, "200", null));
        // BigDecimal 的 equals 连小数位一起比，钱一律用 compareTo
        assertEquals(0, again.getAmount().compareTo(new BigDecimal("200")), again.getAmount().toPlainString());

        // 成立之后本人不能再撤，得走协会取消
        pairService.establish(again.getId(), ADMIN);
        assertTrue(assertThrows(BusinessException.class, () -> pairService.withdraw(id, a))
                .getMessage().contains("联系协会"));
        assertTrue(assertThrows(BusinessException.class, () -> pairService.establish(again.getId(), ADMIN))
                .getMessage().contains("不能确认"), "不能重复确认");
        assertTrue(assertThrows(BusinessException.class, () -> pairService.cancel(again.getId(), "  ", ADMIN))
                .getMessage().contains("取消原因"), "后台取消必须写原因");
    }

    @Test
    void letters_privateOnesOnlyReachTheirOwnDonor() {
        Long id = projectService.create(project("来信-" + next(), "800"), ADMIN);
        projectService.publish(id);
        Long mine = volunteer("收信人");
        Long other = volunteer("别的结对人");
        PairVOs.PairRecord r = pairService.register(id, mine, register(PairFlow.AMOUNT_PARTIAL, "300", null));
        pairService.establish(r.getId(), ADMIN);

        projectService.addLetter(id, letter(null, "谢谢大家"), ADMIN);
        projectService.addLetter(id, letter(r.getId(), "谢谢您资助我读书"), ADMIN);

        List<PairVOs.Letter> forMe = projectService.lettersForVolunteer(id, mine, new PageQuery()).getRecords();
        assertEquals(2, forMe.size(), "公开信 + 写给我的那封");
        assertTrue(forMe.stream().anyMatch(PairVOs.Letter::isForMe));
        assertTrue(forMe.stream().allMatch(l -> l.getPairRecordId() == null), "志愿者端不下发收信人 id");

        List<PairVOs.Letter> forOther = projectService.lettersForVolunteer(id, other, new PageQuery()).getRecords();
        assertEquals(1, forOther.size(), "别人只看得到公开信");
        assertEquals("谢谢大家", forOther.get(0).getContent());
        assertFalse(forOther.get(0).isForMe());

        List<PairVOs.Letter> admin = projectService.lettersForAdmin(id, new PageQuery()).getRecords();
        assertEquals(2, admin.size());
        assertTrue(admin.stream().anyMatch(l -> r.getId().equals(l.getPairRecordId())), "后台看得到收信人");

        assertTrue(assertThrows(BusinessException.class,
                () -> projectService.addLetter(id, letter(999_999_999L, "张冠李戴"), ADMIN))
                .getMessage().contains("不属于这个项目"));
    }

    @Test
    void crowdfund_lifecycleAndTabs() {
        String tag = "众筹-" + next();
        Long id = crowdfundService.create(crowdfund(tag, "20000"), ADMIN);
        assertTrue(assertThrows(BusinessException.class, () -> crowdfundService.detailForVolunteer(id))
                .getMessage().contains("项目不存在"), "草稿对志愿者不可见");

        crowdfundService.publish(id);
        PairVOs.Crowdfund vo = crowdfundService.detailForVolunteer(id);
        assertEquals(PairFlow.CROWDFUND_OPEN, vo.getStatus());
        assertEquals(BigDecimal.ZERO.setScale(2), vo.getRaisedAmount(), "本批没有支付，已筹恒为 0");
        assertEquals(0, vo.getProgressPercent());
        assertEquals(0, vo.getDonorCount());

        assertTrue(crowdfundService.listForVolunteer(new PageQuery(), 1).getRecords().stream()
                .anyMatch(c -> c.getId().equals(id)), "「进行中」页签里有它");
        assertFalse(crowdfundService.listForVolunteer(new PageQuery(), 2).getRecords().stream()
                .anyMatch(c -> c.getId().equals(id)));
        assertThrows(BusinessException.class, () -> crowdfundService.listForVolunteer(new PageQuery(), 7));

        crowdfundService.end(id);
        assertEquals(PairFlow.CROWDFUND_ENDED, crowdfundService.detailForVolunteer(id).getStatus());
        assertTrue(assertThrows(BusinessException.class, () -> crowdfundService.delete(id))
                .getMessage().contains("只有草稿"));
    }

    // ---------- helpers ----------

    private Long volunteer(String name) {
        return BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, name, phone(), true);
    }

    private static PairDTOs.ProjectSave project(String title, String target) {
        PairDTOs.ProjectSave d = new PairDTOs.ProjectSave();
        d.setTitle(title);
        d.setProjectType(PairFlow.TYPE_STUDY);
        d.setDetail("某某同学，家庭困难，需要一年的学费资助。");
        d.setTargetAmount(new BigDecimal(target));
        return d;
    }

    private static PairDTOs.Register register(int amountType, String amount, String remark) {
        PairDTOs.Register d = new PairDTOs.Register();
        d.setAmountType(amountType);
        d.setAmount(amount == null ? null : new BigDecimal(amount));
        d.setRemark(remark);
        return d;
    }

    private static PairDTOs.LetterSave letter(Long pairRecordId, String content) {
        PairDTOs.LetterSave d = new PairDTOs.LetterSave();
        d.setPairRecordId(pairRecordId);
        d.setTitle("来信");
        d.setContent(content);
        d.setImages(List.of("https://cdn.example.com/letter1.jpg"));
        return d;
    }

    private static PairDTOs.CrowdfundSave crowdfund(String title, String target) {
        PairDTOs.CrowdfundSave d = new PairDTOs.CrowdfundSave();
        d.setTitle(title);
        d.setDetail("为山区小学修一条路。");
        d.setTargetAmount(new BigDecimal(target));
        return d;
    }

    private int statusOf(Long pairRecordId) {
        return jdbc.queryForObject("SELECT status FROM donate_pair_record WHERE id = ?", Integer.class, pairRecordId);
    }
}
