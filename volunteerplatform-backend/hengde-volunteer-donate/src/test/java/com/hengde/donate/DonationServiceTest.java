package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonationFlow;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.dto.DonationDTOs;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.service.CrowdfundService;
import com.hengde.donate.service.DonationService;
import com.hengde.donate.service.PairProjectService;
import com.hengde.donate.service.PairService;
import com.hengde.donate.vo.DonationVOs;
import com.hengde.donate.vo.PairVOs;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.service.TradeOrderService;
import com.hengde.trade.vo.TradeVOs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
 * 捐款（V3 捐款批）：众筹捐款与结对捐款付钱的那一半。
 *
 * <p>钉住的是「钱」与「已筹 / 已付」两本账在每条路径上都对得上：到账才计入、退款减回、
 * 事件丢了补偿任务推进、超时取消、超时但渠道已收钱不取消；结对被撤回 / 取消时，待支付的先关交易单、
 * 已到账的原路退款。取消与付款的并发赛跑另见 {@code DonationConcurrencyTest}。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {"hengde.donate.donation.pay-timeout-minutes=30",
        "hengde.donate.donation.max-amount=50000"})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, MallPaymentFakes.class})
class DonationServiceTest {

    @Autowired
    private DonationService donationService;
    @Autowired
    private CrowdfundService crowdfundService;
    @Autowired
    private PairProjectService projectService;
    @Autowired
    private PairService pairService;
    @Autowired
    private TradeOrderService tradeOrderService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private MallPaymentFakes.FakeGateway gateway;
    @Autowired
    private JdbcTemplate jdbc;

    private Long donor;

    @BeforeEach
    void setUp() {
        gateway.reset();
        donor = volunteer("捐款人张三丰");
    }

    // ================= 众筹捐款 =================

    @Test
    void crowdfundDonation_countsOnlyWhenPaid_andPublicRecordsMaskTheName() {
        Long cf = openCrowdfund("20000");
        DonationVOs.Created created = donationService.donateToCrowdfund(cf, donor, crowdfund("50.5", "加油", true));
        DonationVOs.Donation d = created.getDonation();
        assertEquals(DonationFlow.AWAITING_PAYMENT, d.getStatus());
        assertEquals(0, new BigDecimal("50.50").compareTo(d.getAmount()));
        assertEquals(5050, created.getPrepay().getAmountFen(), "进 trade 的是分");
        assertFalse(created.getPrepay().getExpireTime().isAfter(d.getPayExpireTime()), "交易单不能比捐款活得久");
        assertEquals(0, raised(cf).signum(), "没到账不计入已筹");

        paid(created.getPrepay());

        DonationVOs.Donation after = donationService.detailMine(d.getId(), donor);
        assertEquals(DonationFlow.PAID, after.getStatus());
        assertEquals(DonationFlow.INVOICE_PENDING, after.getInvoiceStatus(), "要发票的到账后进入待开票");
        assertEquals(0, new BigDecimal("50.50").compareTo(raised(cf)));
        PairVOs.Crowdfund vo = crowdfundService.detailForVolunteer(cf);
        assertEquals(1, vo.getDonorCount());

        DonationVOs.PublicRecord record = donationService.publicRecords(DonationFlow.BIZ_CROWDFUND, cf, page())
                .getRecords().get(0);
        assertEquals("捐*****", record.getDonorName(), "公开记录姓名打码（保留第一个字）");
        assertEquals("加油", record.getRemark());
    }

    @Test
    void crowdfundDonation_isRefusedUpfront_andLeavesNothingBehind() {
        Long cf = openCrowdfund("20000");
        assertMessage("最多两位小数", () -> donationService.donateToCrowdfund(cf, donor, crowdfund("1.001", null, false)));
        assertMessage("不超过", () -> donationService.donateToCrowdfund(cf, donor, crowdfund("50000.01", null, false)));
        DonationDTOs.CrowdfundDonate noTitle = crowdfund("10", null, true);
        noTitle.setInvoiceTitle(null);
        assertMessage("发票抬头", () -> donationService.donateToCrowdfund(cf, donor, noTitle));
        DonationDTOs.CrowdfundDonate titleWithoutNeed = crowdfund("10", null, false);
        titleWithoutNeed.setInvoiceTitle("某公司");
        assertMessage("不需要发票", () -> donationService.donateToCrowdfund(cf, donor, titleWithoutNeed));
        DonationDTOs.CrowdfundDonate badCode = crowdfund("10", null, false);
        badCode.setCode("bad");
        assertThrows(BusinessException.class, () -> donationService.donateToCrowdfund(cf, donor, badCode));

        gateway.enabled = false;
        assertMessage("微信支付尚未开通", () -> donationService.donateToCrowdfund(cf, donor, crowdfund("10", null, false)));
        gateway.enabled = true;

        Long draft = crowdfundService.create(crowdfundSave("草稿众筹-" + next(), "1000"), ADMIN);
        assertMessage("不在募集中", () -> donationService.donateToCrowdfund(draft, donor, crowdfund("10", null, false)));
        crowdfundService.end(cf);
        assertMessage("不在募集中", () -> donationService.donateToCrowdfund(cf, donor, crowdfund("10", null, false)));

        assertEquals(0, donationCount(donor), "被拒的一笔都不该落库——否则每一次失败都留下一笔没人付的待支付");
        assertEquals(0, gateway.prepays.get());
    }

    // ================= 结对捐款 =================

    @Test
    void pairDonation_paysTheRemainingPledge_once() {
        Long project = openPairProject("1000");
        PairVOs.PairRecord r = pairService.register(project, donor, register("300"));
        assertMessage("请先登记结对", () -> donationService.donateToPair(project, volunteer("没登记的人"), pair()));

        DonationVOs.Created created = donationService.donateToPair(project, donor, pair());
        assertEquals(0, new BigDecimal("300").compareTo(created.getDonation().getAmount()), "付的是认捐额 − 已付，不让人再填一次");
        assertEquals(r.getId(), created.getDonation().getPairRecordId());
        assertMessage("待付款", () -> donationService.donateToPair(project, donor, pair()),
                "一条结对至多一笔待支付——两笔同时付成就超付了");

        paid(created.getPrepay());

        assertEquals(0, new BigDecimal("300").compareTo(paidOf(r.getId())));
        assertEquals(0, new BigDecimal("300").compareTo(projectRaised(project)), "项目「已筹」展示的是到账额");
        assertEquals(0, projectService.detailForAdmin(project).getPledgedAmount().signum(),
                "认捐额仍只在确认结对成立时累加——付款不动它");
        assertMessage("已经付清", () -> donationService.donateToPair(project, donor, pair()));
        assertEquals(1, donationService.publicRecords(DonationFlow.BIZ_PAIR, project, page()).getRecords().size());
    }

    @Test
    void withdrawingAPairClosesItsAwaitingDonation_butAPaidPairCannotBeWithdrawn() {
        Long project = openPairProject("1000");
        pairService.register(project, donor, register("200"));
        DonationVOs.Created awaiting = donationService.donateToPair(project, donor, pair());

        pairService.withdraw(project, donor);

        assertEquals(DonationFlow.CANCELLED, statusOf(awaiting.getDonation().getId()), "撤回结对，待支付的捐款随之取消");
        assertEquals(TradeFlow.ORDER_CLOSED, tradeStatus(awaiting.getPrepay().getOutTradeNo()), "交易单先关掉");

        Long other = volunteer("付过款的人");
        pairService.register(project, other, register("100"));
        paid(donationService.donateToPair(project, other, pair()).getPrepay());
        assertMessage("已经付过款", () -> pairService.withdraw(project, other));
    }

    @Test
    void cancellingAPaidPair_refundsTheMoneyAfterTheCancelCommits() {
        Long project = openPairProject("1000");
        PairVOs.PairRecord r = pairService.register(project, donor, register("400"));
        pairService.establish(r.getId(), ADMIN);
        DonationVOs.Created created = donationService.donateToPair(project, donor, pair());
        paid(created.getPrepay());

        pairService.cancel(r.getId(), "受助人已转学", ADMIN);

        assertEquals(PairFlow.PAIR_CANCELLED, jdbc.queryForObject("SELECT status FROM donate_pair_record WHERE id = ?",
                Integer.class, r.getId()));
        assertEquals(DonationFlow.REFUNDED, statusOf(created.getDonation().getId()), "已到账的钱原路退回");
        assertEquals(0, paidOf(r.getId()).signum());
        assertEquals(0, projectRaised(project).signum());
        assertEquals(TradeFlow.ORDER_REFUNDED, tradeStatus(created.getPrepay().getOutTradeNo()));
        assertEquals(1, gateway.refunds.size());
    }

    // ================= 取消 / 补偿 =================

    @Test
    void cancellingAnAwaitingDonationClosesTheTradeOrder_butAPaidOneCannotBeCancelled() {
        Long cf = openCrowdfund("5000");
        DonationVOs.Created awaiting = donationService.donateToCrowdfund(cf, donor, crowdfund("20", null, false));
        assertThrows(BusinessException.class, () -> donationService.cancel(awaiting.getDonation().getId(), volunteer("别人")));
        donationService.cancel(awaiting.getDonation().getId(), donor);
        assertEquals(DonationFlow.CANCELLED, statusOf(awaiting.getDonation().getId()));
        assertTrue(gateway.closed.contains(awaiting.getPrepay().getOutTradeNo()));

        DonationVOs.Created done = donationService.donateToCrowdfund(cf, donor, crowdfund("30", null, false));
        paid(done.getPrepay());
        assertMessage("只有待支付", () -> donationService.cancel(done.getDonation().getId(), donor));
        assertMessage("不需要付款", () -> donationService.pay(done.getDonation().getId(), donor, "c"));
    }

    @Test
    void cancelIsRefusedWhenThePaymentJustLanded() {
        Long cf = openCrowdfund("5000");
        DonationVOs.Created c = donationService.donateToCrowdfund(cf, donor, crowdfund("40", null, false));
        // 交易单刚被推成已支付、捐款这边还没收到事件（那一瞬间）
        jdbc.update("UPDATE trade_order SET status = ? WHERE out_trade_no = ?", TradeFlow.ORDER_PAID,
                c.getPrepay().getOutTradeNo());

        assertMessage("刚刚已付款", () -> donationService.cancel(c.getDonation().getId(), donor));
        assertEquals(DonationFlow.AWAITING_PAYMENT, statusOf(c.getDonation().getId()), "取消必须放弃，什么都不动");
    }

    @Test
    void lostEvent_timeout_andPaidOnChannelOnly_areAllSettledByTheSyncJob() {
        Long cf = openCrowdfund("5000");
        DonationVOs.Created lost = donationService.donateToCrowdfund(cf, donor, crowdfund("10", null, false));
        jdbc.update("UPDATE trade_order SET status = ?, pay_time = NOW() WHERE out_trade_no = ?", TradeFlow.ORDER_PAID,
                lost.getPrepay().getOutTradeNo());

        DonationVOs.Created timedOut = donationService.donateToCrowdfund(cf, donor, crowdfund("11", null, false));
        expire(timedOut);

        DonationVOs.Created channelOnly = donationService.donateToCrowdfund(cf, donor, crowdfund("12", null, false));
        gateway.markPaidOnRemote(channelOnly.getPrepay().getOutTradeNo(), "wx-" + next(), 1200);
        expire(channelOnly);

        donationService.syncAwaitingDonations();

        assertEquals(DonationFlow.PAID, statusOf(lost.getDonation().getId()), "事件丢了，补偿任务照样到账");
        assertEquals(DonationFlow.CANCELLED, statusOf(timedOut.getDonation().getId()), "超时没付的取消");
        assertEquals(DonationFlow.PAID, statusOf(channelOnly.getDonation().getId()), "超时但渠道已收到钱的不能取消");
        assertEquals(0, new BigDecimal("22").compareTo(raised(cf)), "已筹 = 10 + 12");
    }

    // ================= 退款与开票 =================

    @Test
    void refund_subtractsRaised_andRecordsARefundFailureWithoutUndoingIt() {
        Long cf = openCrowdfund("5000");
        DonationVOs.Created a = donationService.donateToCrowdfund(cf, donor, crowdfund("100", null, false));
        DonationVOs.Created b = donationService.donateToCrowdfund(cf, donor, crowdfund("60", null, false));
        paid(a.getPrepay());
        paid(b.getPrepay());
        assertEquals(0, new BigDecimal("160").compareTo(raised(cf)));

        donationService.refund(a.getDonation().getId(), "捐错项目了", ADMIN);
        assertEquals(DonationFlow.REFUNDED, statusOf(a.getDonation().getId()));
        assertEquals(0, new BigDecimal("60").compareTo(raised(cf)), "退款从已筹里减回");
        assertEquals(TradeFlow.ORDER_REFUNDED, tradeStatus(a.getPrepay().getOutTradeNo()));
        assertMessage("只有已到账", () -> donationService.refund(a.getDonation().getId(), "再退一次", ADMIN));

        gateway.refundFails = true;
        donationService.refund(b.getDonation().getId(), "渠道故障演练", ADMIN);
        DonationVOs.Donation failed = donationService.listForAdmin(page(), null, cf, DonationFlow.REFUNDED, null,
                        b.getDonation().getDonationNo()).getRecords().get(0);
        assertNotNull(failed.getCashRefundError(), "钱还没退出去这件事必须看得见");
        assertNull(failed.getCashRefundNo());
        assertEquals(0, raised(cf).signum(), "退款发起失败不回滚退款标记，已筹照样减回（钱要在收付页重试退出去）");
    }

    @Test
    void invoice_canOnlyBeRegisteredForPaidDonationsThatAskedForIt_andBlocksRefund() {
        Long cf = openCrowdfund("5000");
        DonationVOs.Created want = donationService.donateToCrowdfund(cf, donor, crowdfund("88", null, true));
        DonationVOs.Created noNeed = donationService.donateToCrowdfund(cf, donor, crowdfund("66", null, false));
        assertMessage("不能登记开票", () -> donationService.markInvoiced(want.getDonation().getId(), "FP001", ADMIN),
                "没到账的不能开票");
        paid(want.getPrepay());
        paid(noNeed.getPrepay());
        assertMessage("不能登记开票", () -> donationService.markInvoiced(noNeed.getDonation().getId(), "FP002", ADMIN));

        donationService.markInvoiced(want.getDonation().getId(), "FP001", ADMIN);
        assertEquals("FP001", donationService.detailMine(want.getDonation().getId(), donor).getInvoiceNo());
        assertMessage("先作废发票", () -> donationService.refund(want.getDonation().getId(), "想退", ADMIN));
    }

    @Test
    void maskKeepsTheFirstCharacter() {
        assertEquals("张*", invokeMask("张三"));
        assertEquals("欧**", invokeMask("欧阳锋"));
        assertEquals("李*", invokeMask("李"));
        assertEquals("爱心人士", invokeMask(null));
    }

    // ---------------- helpers ----------------

    private static String invokeMask(String name) {
        try {
            java.lang.reflect.Method m = DonationService.class.getDeclaredMethod("mask", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void paid(TradeVOs.Prepay prepay) {
        assertTrue(tradeOrderService.applyPaidResult(prepay.getOutTradeNo(), "wx-" + next(), prepay.getAmountFen(),
                LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK));
    }

    private void expire(DonationVOs.Created c) {
        jdbc.update("UPDATE donate_donation SET pay_expire_time = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1),
                c.getDonation().getId());
        jdbc.update("UPDATE trade_order SET expire_time = ? WHERE out_trade_no = ?", LocalDateTime.now().minusMinutes(1),
                c.getPrepay().getOutTradeNo());
    }

    private Long openCrowdfund(String target) {
        Long id = crowdfundService.create(crowdfundSave("捐款众筹-" + next(), target), ADMIN);
        crowdfundService.publish(id);
        return id;
    }

    private Long openPairProject(String target) {
        PairDTOs.ProjectSave d = new PairDTOs.ProjectSave();
        d.setTitle("捐款结对-" + next());
        d.setProjectType(PairFlow.TYPE_STUDY);
        d.setTargetAmount(new BigDecimal(target));
        Long id = projectService.create(d, ADMIN);
        projectService.publish(id);
        return id;
    }

    private static PairDTOs.CrowdfundSave crowdfundSave(String title, String target) {
        PairDTOs.CrowdfundSave d = new PairDTOs.CrowdfundSave();
        d.setTitle(title);
        d.setTargetAmount(new BigDecimal(target));
        return d;
    }

    private static PairDTOs.Register register(String amount) {
        PairDTOs.Register d = new PairDTOs.Register();
        d.setAmountType(PairFlow.AMOUNT_PARTIAL);
        d.setAmount(new BigDecimal(amount));
        return d;
    }

    static DonationDTOs.CrowdfundDonate crowdfund(String amount, String remark, boolean invoice) {
        DonationDTOs.CrowdfundDonate d = new DonationDTOs.CrowdfundDonate();
        d.setAmount(new BigDecimal(amount));
        d.setRemark(remark);
        d.setCode("code-" + next());
        if (invoice) {
            d.setNeedInvoice(true);
            d.setInvoiceTitle("个人");
        }
        return d;
    }

    static DonationDTOs.PairDonate pair() {
        DonationDTOs.PairDonate d = new DonationDTOs.PairDonate();
        d.setCode("code-" + next());
        return d;
    }

    private Long volunteer(String name) {
        return BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, name, phone(), true);
    }

    private static PageQuery page() {
        PageQuery q = new PageQuery();
        q.setPage(1);
        q.setSize(50);
        return q;
    }

    private BigDecimal raised(Long crowdfundId) {
        return jdbc.queryForObject("SELECT raised_amount FROM donate_crowdfund WHERE id = ?", BigDecimal.class, crowdfundId);
    }

    private BigDecimal projectRaised(Long projectId) {
        return jdbc.queryForObject("SELECT raised_amount FROM donate_pair_project WHERE id = ?", BigDecimal.class, projectId);
    }

    private BigDecimal paidOf(Long recordId) {
        return jdbc.queryForObject("SELECT paid_amount FROM donate_pair_record WHERE id = ?", BigDecimal.class, recordId);
    }

    private int statusOf(Long donationId) {
        return jdbc.queryForObject("SELECT status FROM donate_donation WHERE id = ?", Integer.class, donationId);
    }

    private int tradeStatus(String outTradeNo) {
        return jdbc.queryForObject("SELECT status FROM trade_order WHERE out_trade_no = ?", Integer.class, outTradeNo);
    }

    private int donationCount(Long volunteerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM donate_donation WHERE volunteer_id = ?", Integer.class, volunteerId);
    }

    private static void assertMessage(String expected, org.junit.jupiter.api.function.Executable action) {
        assertMessage(expected, action, "");
    }

    private static void assertMessage(String expected, org.junit.jupiter.api.function.Executable action, String why) {
        BusinessException e = assertThrows(BusinessException.class, action, why);
        assertTrue(e.getMessage().contains(expected), why + "（实际：" + e.getMessage() + "）");
    }
}
