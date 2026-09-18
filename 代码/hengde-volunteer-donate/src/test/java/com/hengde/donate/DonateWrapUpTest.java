package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.constant.DonationFlow;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.service.BookCampaignService;
import com.hengde.donate.service.CrowdfundService;
import com.hengde.donate.service.DonateMasterDataService;
import com.hengde.donate.service.DonateRecordService;
import com.hengde.donate.service.DonateShipmentService;
import com.hengde.donate.service.DonateStatsService;
import com.hengde.donate.service.DonationService;
import com.hengde.donate.service.PairProjectService;
import com.hengde.donate.service.PairService;
import com.hengde.donate.service.WishService;
import com.hengde.donate.vo.DonateFlowVOs;
import com.hengde.donate.vo.DonateStatsVOs;
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
import java.util.List;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.check;
import static com.hengde.donate.BookDonationTestSupport.expressNo;
import static com.hengde.donate.BookDonationTestSupport.item;
import static com.hengde.donate.BookDonationTestSupport.next;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static com.hengde.donate.BookDonationTestSupport.result;
import static com.hengde.donate.BookDonationTestSupport.shipment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V3 收尾批：「我的捐赠记录」（Row 33）、结对中心详情（Row 34）、后台捐赠数据汇总（Row 79）。
 *
 * <p>数据汇总是全库聚合，所以断言<b>增量</b>（前后各取一次相减），并且每一条口径都配一个<b>不该被算进去</b>的反例——
 * 只造「该算的」数据时，把条件整个删掉用例也照样绿。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {WishTestSupport.RECV_NAME, WishTestSupport.RECV_PHONE, WishTestSupport.RECV_ADDRESS})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, MallPaymentFakes.class})
class DonateWrapUpTest {

    @Autowired
    private DonateRecordService recordService;
    @Autowired
    private DonateStatsService statsService;
    @Autowired
    private DonationService donationService;
    @Autowired
    private CrowdfundService crowdfundService;
    @Autowired
    private PairProjectService projectService;
    @Autowired
    private PairService pairService;
    @Autowired
    private WishService wishService;
    @Autowired
    private BookCampaignService campaignService;
    @Autowired
    private DonateShipmentService shipmentService;
    @Autowired
    private DonateMasterDataService masterDataService;
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

    @BeforeEach
    void setUp() {
        gateway.reset();
    }

    // ================= Row 33 我的捐赠记录 =================

    @Test
    void myRecords_mergeMoneyAndGoodsIntoOneTimeline_pagedInTheDatabase() {
        Long me = volunteer("时间线捐赠人");
        String title = "时间线众筹-" + next();
        Long cf = openCrowdfund(title);

        DonationVOs.Created d1 = donationService.donateToCrowdfund(cf, me, DonationServiceTest.crowdfund("10", null, false));
        DonateFlowVOs.Shipment s1 = crowdfundService.registerGoods(cf, me, shipment(expressNo(),
                item("书包", DonateFlow.TYPE_STATIONERY, 1, null)));
        DonationVOs.Created d2 = donationService.donateToCrowdfund(cf, me, DonationServiceTest.crowdfund("20", null, false));
        paid(d2.getPrepay());
        DonateFlowVOs.Shipment s2 = crowdfundService.registerGoods(cf, me, shipment(expressNo(),
                item("铅笔", DonateFlow.TYPE_STATIONERY, 10, null), item("足球", DonateFlow.TYPE_SPORTS, 1, null)));
        // 时间拉开，定序才有得断言；d2 与 s1 故意同一秒，验证同一时刻的次序也是确定的（捐款在前）
        LocalDateTime base = LocalDateTime.now().withNano(0);
        jdbc.update("UPDATE donate_donation SET create_time = ? WHERE id = ?", base.minusHours(4), d1.getDonation().getId());
        jdbc.update("UPDATE donate_shipment SET ship_time = ? WHERE id = ?", base.minusHours(2), s1.getId());
        jdbc.update("UPDATE donate_donation SET create_time = ? WHERE id = ?", base.minusHours(2), d2.getDonation().getId());
        jdbc.update("UPDATE donate_shipment SET ship_time = ?, track_last_context = '已揽收' WHERE id = ?",
                base.minusHours(1), s2.getId());

        // 不该出现的：别人的捐款、我的结对捐款（在结对中心）、我的捐书运单（在捐书记录）
        donationService.donateToCrowdfund(cf, volunteer("别人"), DonationServiceTest.crowdfund("30", null, false));
        Long project = openPairProject("1000");
        pairService.register(project, me, register("100"));
        donationService.donateToPair(project, me, DonationServiceTest.pair());
        shipmentService.register(me, BookDonationTestSupport.openCampaign(campaignService),
                shipment(expressNo(), item("课外书", DonateFlow.TYPE_BOOK, 1, null)));

        List<DonationVOs.MyRecord> page1 = recordService.mine(me, 0, page(1, 3)).getRecords();
        List<DonationVOs.MyRecord> page2 = recordService.mine(me, 0, page(2, 3)).getRecords();
        assertEquals(4, recordService.mine(me, 0, page(1, 3)).getTotal());
        assertEquals(List.of("2:" + s2.getId(), "1:" + d2.getDonation().getId(), "2:" + s1.getId()), keys(page1),
                "时间倒序；同一时刻捐款在前");
        assertEquals(List.of("1:" + d1.getDonation().getId()), keys(page2), "第 2 页接得上、不重复不漏");

        DonationVOs.MyRecord goods = page1.get(0);
        assertEquals("捐物", goods.getKindLabel());
        assertEquals(title, goods.getProjectTitle(), "捐物的项目名按项目 id 去众筹表取");
        assertEquals(2, goods.getItemCount());
        assertEquals("已寄出", goods.getStatusLabel());
        assertEquals("已揽收", goods.getTrackLastContext());
        assertNull(goods.getAmount());
        DonationVOs.MyRecord money = page1.get(1);
        assertEquals("捐款", money.getKindLabel());
        assertEquals(title, money.getProjectTitle());
        assertEquals(0, new BigDecimal("20").compareTo(money.getAmount()));
        assertEquals("已到账", money.getStatusLabel());

        assertEquals(List.of("1:" + d2.getDonation().getId(), "1:" + d1.getDonation().getId()),
                keys(recordService.mine(me, 1, page(1, 10)).getRecords()), "只看捐款");
        assertEquals(List.of("2:" + s2.getId(), "2:" + s1.getId()),
                keys(recordService.mine(me, 2, page(1, 10)).getRecords()), "只看捐物");
        assertEquals(0, recordService.mine(me, 0, page(3, 3)).getRecords().size(), "翻过头是空页，不报错");
        assertEquals("kind 只能是 0全部 / 1捐款 / 2捐物",
                assertThrows(BusinessException.class, () -> recordService.mine(me, 5, page(1, 10))).getMessage());
    }

    // ================= Row 34 结对中心详情 =================

    @Test
    void pairCenter_showsEveryDonationOfThePair_andOnlyToItsOwner() {
        Long me = volunteer("结对中心");
        Long project = openPairProject("1000");
        PairVOs.PairRecord r = pairService.register(project, me, register("300"));
        pairService.establish(r.getId(), ADMIN);

        DonationVOs.Created first = donationService.donateToPair(project, me, DonationServiceTest.pair());
        assertEquals(0, new BigDecimal("300").compareTo(pairService.myPairDetail(r.getId(), me).getRemainingAmount()));
        donationService.cancel(first.getDonation().getId(), me);
        DonationVOs.Created second = donationService.donateToPair(project, me, DonationServiceTest.pair());
        paid(second.getPrepay());

        PairVOs.PairCenter detail = pairService.myPairDetail(r.getId(), me);
        assertEquals(PairFlow.PAIR_ESTABLISHED, detail.getRecord().getStatus());
        assertEquals(0, new BigDecimal("300").compareTo(detail.getRecord().getPaidAmount()));
        assertEquals(0, detail.getRemainingAmount().signum(), "付清了");
        assertEquals(List.of(second.getDonation().getId(), first.getDonation().getId()),
                detail.getDonations().stream().map(DonationVOs.Donation::getId).toList(), "每一笔都在，新的在前");
        assertEquals(List.of(DonationFlow.PAID, DonationFlow.CANCELLED),
                detail.getDonations().stream().map(DonationVOs.Donation::getStatus).toList());

        assertEquals("结对记录不存在", assertThrows(BusinessException.class,
                () -> pairService.myPairDetail(r.getId(), volunteer("别人"))).getMessage(), "别人的与不存在同一句话");
        assertEquals("结对记录不存在", assertThrows(BusinessException.class,
                () -> pairService.myPairDetail(-1L, me)).getMessage());
    }

    // ================= Row 79 捐赠数据汇总 =================

    @Test
    void donationSummary_countsWhatTheDefinitionsSay_andNothingElse() {
        DonateStatsVOs.Summary before = statsService.summary();
        Long v1 = volunteer("汇总一");
        Long v2 = volunteer("汇总二");
        Long org = BookDonationTestSupport.org(masterDataService);

        // ---- 微心愿 ----
        Long w1 = wishService.create(WishTestSupport.wish("汇总心愿一-" + next(), "小明", org), ADMIN);
        Long w2 = wishService.create(WishTestSupport.wish("汇总心愿二-" + next(), "小红", org), ADMIN);
        Long w3 = wishService.create(WishTestSupport.wish("汇总心愿三-" + next(), "小刚", org), ADMIN);
        wishService.claim(w1, v1);
        wishService.claim(w2, v2);
        wishService.revokeClaim(w2, "资料有误", ADMIN);   // 被撤销：不算认领成功、不算参与
        wishService.claim(w3, v1);
        wishService.cancelClaim(w3, v1);                 // 本人取消：心愿回池（不算认领成功），但他参与过
        DonateFlowVOs.Shipment wishParcel = wishService.registerShipment(w1, v1, shipment(expressNo(),
                item("书包", DonateFlow.TYPE_STATIONERY, 1, null)));
        shipmentService.arrive(wishParcel.getId(), ADMIN);
        wishService.registerShipment(w1, v1, shipment(expressNo(), item("文具盒", DonateFlow.TYPE_STATIONERY, 1, null)));

        // ---- 结对 ----
        Long p1 = openPairProject("1000");
        projectService.create(pairProject("草稿不算发布"), ADMIN);
        PairVOs.PairRecord established = pairService.register(p1, v1, register("300"));
        pairService.establish(established.getId(), ADMIN);
        pairService.register(p1, v2, register("100"));
        pairService.withdraw(p1, v2);                     // 登记过就算一人次，但没结对成功

        // ---- 捐书 ----
        Long c1 = BookDonationTestSupport.openCampaign(campaignService);
        DonateFlowVOs.Shipment book = shipmentService.register(v1, c1, shipment(expressNo(),
                item("课外书", DonateFlow.TYPE_BOOK, 3, null), item("破文具", DonateFlow.TYPE_STATIONERY, 2, null),
                item("跳绳", DonateFlow.TYPE_SPORTS, 1, null)));
        shipmentService.arrive(book.getId(), ADMIN);
        shipmentService.check(book.getId(), check(
                result(book.getItems().get(0).getId(), true, null),
                result(book.getItems().get(1).getId(), false, "坏了"),   // 不合格：不算「收到」
                result(book.getItems().get(2).getId(), true, null)), ADMIN);
        DonateFlowVOs.Shipment cancelled = shipmentService.register(v2, c1, shipment(expressNo(),
                item("课外书", DonateFlow.TYPE_BOOK, 5, null)));
        shipmentService.cancel(cancelled.getId(), v2);   // 取消的运单：不算参加
        Long c2 = BookDonationTestSupport.openCampaign(campaignService);
        DonateFlowVOs.Shipment late = shipmentService.register(v2, c2, shipment(expressNo(),
                item("课外书", DonateFlow.TYPE_BOOK, 7, null)));
        shipmentService.arrive(late.getId(), ADMIN);
        jdbc.update("UPDATE donate_campaign SET stats_deadline = ? WHERE id = ?", LocalDateTime.now().minusDays(2), c2);

        DonateStatsVOs.Summary after = statsService.summary();

        assertEquals(3, after.getWish().getPublished() - before.getWish().getPublished());
        assertEquals(1, after.getWish().getClaimed() - before.getWish().getClaimed(), "只有 w1 此刻是已认领");
        assertEquals(0, after.getWish().getRealized() - before.getWish().getRealized());
        assertEquals(2, after.getWish().getParticipations() - before.getWish().getParticipations(),
                "v1 认领两次（含本人取消的那次），v2 那次被撤销不算");
        assertEquals(1, after.getWish().getParticipants() - before.getWish().getParticipants());
        assertEquals(1, after.getWish().getParcels() - before.getWish().getParcels(), "在途的那一包还没收到");

        assertEquals(1, after.getPair().getPublished() - before.getPair().getPublished());
        assertEquals(1, after.getPair().getEstablished() - before.getPair().getEstablished());
        assertEquals(2, after.getPair().getParticipations() - before.getPair().getParticipations());
        assertEquals(0, new BigDecimal("300").compareTo(
                after.getPair().getPledgedAmount().subtract(before.getPair().getPledgedAmount())));
        assertEquals(0, after.getPair().getRaisedAmount().compareTo(before.getPair().getRaisedAmount()), "没付款，到账额不动");

        assertEquals(1, after.getBook().getParticipants() - before.getBook().getParticipants(),
                "v2 的一单取消了、另一单在统计截止之后");
        assertEquals(1, after.getBook().getParcels() - before.getBook().getParcels());
        assertEquals(3, after.getBook().getBooks() - before.getBook().getBooks());
        assertEquals(0, after.getBook().getStationery() - before.getBook().getStationery(), "不合格的不算收到");
        assertEquals(1, after.getBook().getSports() - before.getBook().getSports());
        assertNull(after.getBook().getBookHouses(), "系统里没有书屋这个概念，给 null 不给 0");
    }

    // ---------------- helpers ----------------

    private Long volunteer(String name) {
        return BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, name, phone(), true);
    }

    private Long openCrowdfund(String title) {
        PairDTOs.CrowdfundSave d = new PairDTOs.CrowdfundSave();
        d.setTitle(title);
        d.setTargetAmount(new BigDecimal("5000"));
        d.setAcceptGoods(true);
        d.setRecvName("协会物资组");
        d.setRecvPhone("0759-8888888");
        d.setRecvAddress("雷州市某路 1 号");
        Long id = crowdfundService.create(d, ADMIN);
        crowdfundService.publish(id);
        return id;
    }

    private Long openPairProject(String target) {
        PairDTOs.ProjectSave d = pairProject("收尾批结对-" + next());
        d.setTargetAmount(new BigDecimal(target));
        Long id = projectService.create(d, ADMIN);
        projectService.publish(id);
        return id;
    }

    private static PairDTOs.ProjectSave pairProject(String title) {
        PairDTOs.ProjectSave d = new PairDTOs.ProjectSave();
        d.setTitle(title);
        d.setProjectType(PairFlow.TYPE_STUDY);
        d.setTargetAmount(new BigDecimal("1000"));
        return d;
    }

    private static PairDTOs.Register register(String amount) {
        PairDTOs.Register d = new PairDTOs.Register();
        d.setAmountType(PairFlow.AMOUNT_PARTIAL);
        d.setAmount(new BigDecimal(amount));
        return d;
    }

    private void paid(TradeVOs.Prepay prepay) {
        tradeOrderService.applyPaidResult(prepay.getOutTradeNo(), "wx-" + next(), prepay.getAmountFen(),
                LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK);
    }

    private static List<String> keys(List<DonationVOs.MyRecord> records) {
        return records.stream().map(r -> r.getKind() + ":" + r.getRefId()).toList();
    }

    private static PageQuery page(int page, int size) {
        PageQuery q = new PageQuery();
        q.setPage(page);
        q.setSize(size);
        return q;
    }
}
