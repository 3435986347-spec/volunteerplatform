package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.dao.DonateShipmentMapper;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.service.CrowdfundService;
import com.hengde.donate.service.DonateShipmentService;
import com.hengde.donate.service.DonateTrackService;
import com.hengde.donate.service.DonationService;
import com.hengde.donate.vo.DonateFlowVOs;
import com.hengde.donate.vo.PairVOs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.check;
import static com.hengde.donate.BookDonationTestSupport.expressNo;
import static com.hengde.donate.BookDonationTestSupport.item;
import static com.hengde.donate.BookDonationTestSupport.next;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static com.hengde.donate.BookDonationTestSupport.result;
import static com.hengde.donate.BookDonationTestSupport.shipment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目众筹·捐物（V3 捐款批，Row 16「一个是捐款，一个是捐物」）。
 *
 * <p>物资流转复用捐书批的地基（运单 {@code biz_type = 3}、{@code biz_id} = 众筹项目 id），所以这里钉的是<b>接缝</b>：
 * 项目「收钱 / 收物」两个开关的校验、捐物的资格、运单挂对了来源（来源名、后台按来源筛、查轨迹用项目的收件电话），
 * 以及只收物的项目不许在线捐款。到货 / 核对这些下游动作只走一步，证明它们对这类运单同样可用。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, MallPaymentFakes.class})
class CrowdfundGoodsTest {

    @Autowired
    private CrowdfundService crowdfundService;
    @Autowired
    private DonateShipmentService shipmentService;
    @Autowired
    private DonateTrackService trackService;
    @Autowired
    private DonationService donationService;
    @Autowired
    private DonateShipmentMapper shipmentMapper;
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
        donor = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "捐物人", phone(), true);
    }

    @Test
    void saveRules_acceptSwitchesAndReceiverInfo() {
        // 什么都不传：沿用结对批的行为——只收钱
        Long plain = crowdfundService.create(save("只收钱-" + next(), null, null), ADMIN);
        PairVOs.Crowdfund vo = crowdfundService.detailForAdmin(plain);
        assertTrue(vo.getAcceptMoney());
        assertFalse(vo.getAcceptGoods());
        assertNull(vo.getRecvPhone());

        assertMessage("项目至少要接受捐款或捐物中的一种",
                () -> crowdfundService.create(save("都不收-" + next(), false, false), ADMIN));
        assertMessage("接受捐物请填写物资收件人、电话与地址",
                () -> crowdfundService.create(save("收物没地址-" + next(), true, true), ADMIN));
        assertMessage("不接受捐物时不用填写物资需求与收件信息",
                () -> crowdfundService.create(withRecv(save("只收钱却填地址-" + next(), true, false)), ADMIN),
                "收件信息只对捐物有意义，填了又不收物会让志愿者以为可以寄");

        // 改成只收物：收件信息写进去；再改回只收钱：收件信息被清掉（显式 set，不靠 updateById 跳 null）
        crowdfundService.update(plain, withRecv(save("改成收物-" + next(), false, true)));
        vo = crowdfundService.detailForAdmin(plain);
        assertFalse(vo.getAcceptMoney());
        assertTrue(vo.getAcceptGoods());
        assertEquals("0759-8888888", vo.getRecvPhone());
        assertEquals("书包、文具", vo.getGoodsNeeded());
        crowdfundService.update(plain, save("改回收钱-" + next(), true, false));
        vo = crowdfundService.detailForAdmin(plain);
        assertFalse(vo.getAcceptGoods());
        assertNull(vo.getRecvName());
        assertNull(vo.getRecvAddress());
        assertNull(vo.getGoodsNeeded());
    }

    @Test
    void goodsDonation_isAShipmentOfTheCrowdfund_andFlowsThroughTheWarehouse() {
        String title = "修路物资-" + next();
        Long cf = open(withRecv(save(title, true, true)));

        String no = expressNo();
        DonateFlowVOs.Shipment s = crowdfundService.registerGoods(cf, donor, shipment(no,
                item("水泥", DonateFlow.TYPE_OTHER, 2, null),
                item("铁锹", DonateFlow.TYPE_OTHER, 1, null)));
        assertEquals(DonateFlow.BIZ_CROWDFUND_GOODS, s.getBizType());
        assertEquals(cf, s.getBizId(), "众筹捐物的 biz_id 就是项目 id");
        assertEquals("项目众筹 · " + title, s.getCampaignTitle(),
                "来源名按类型查项目表，不是拿项目 id 去捐书活动表对（撞上同号活动就显示成别人的名字）");

        assertEquals("0759-8888888", trackService.phoneFor(shipmentMapper.selectById(s.getId())),
                "顺丰查轨迹要收件电话：取项目上的，不是空");

        List<DonateFlowVOs.Shipment> listed = shipmentService.listForAdmin(page(), DonateFlow.BIZ_CROWDFUND_GOODS, cf,
                null, null, null, null).getRecords();
        assertEquals(1, listed.size());
        assertEquals(s.getId(), listed.get(0).getId());
        assertTrue(shipmentService.listForAdmin(page(), null, null, null, null, no, null).getRecords().isEmpty(),
                "不传 bizType 仍只看捐书活动，原有列表的行为不变");
        assertMessage("bizType 只能是 1捐书活动 / 2微心愿 / 3众筹捐物",
                () -> shipmentService.listForAdmin(page(), 9, null, null, null, null, null));

        // 仓库那一套对这类运单同样可用
        shipmentService.arrive(s.getId(), ADMIN);
        shipmentService.check(s.getId(), check(result(s.getItems().get(0).getId(), true, null),
                result(s.getItems().get(1).getId(), false, "锈了")), ADMIN);
        assertEquals(DonateFlow.SHIPMENT_CHECKED, jdbc.queryForObject("SELECT status FROM donate_shipment WHERE id = ?",
                Integer.class, s.getId()));

        assertMessage("该快递单号已经登记过，请核对是否填错", () -> crowdfundService.registerGoods(cf, donor,
                shipment(no, item("再来一包", DonateFlow.TYPE_OTHER, 1, null))));
    }

    @Test
    void goodsDonation_refusals() {
        Long moneyOnly = open(save("只收钱-" + next(), true, false));
        assertMessage("这个项目不接受捐物", () -> crowdfundService.registerGoods(moneyOnly, donor, parcel()));

        Long draft = crowdfundService.create(withRecv(save("草稿-" + next(), true, true)), ADMIN);
        assertMessage("这个项目当前不在募集中", () -> crowdfundService.registerGoods(draft, donor, parcel()));

        Long ended = open(withRecv(save("已结束-" + next(), true, true)));
        crowdfundService.end(ended);
        assertMessage("这个项目当前不在募集中", () -> crowdfundService.registerGoods(ended, donor, parcel()));

        Long notStarted = crowdfundService.create(withRecv(save("还没开始-" + next(), true, true),
                d -> d.setStartTime(LocalDateTime.now().plusDays(1))), ADMIN);
        crowdfundService.publish(notStarted);
        assertMessage("这个项目当前不在募集中", () -> crowdfundService.registerGoods(notStarted, donor, parcel()));

        Long ok = open(withRecv(save("要实名-" + next(), true, true)));
        Long guest = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "游客", phone(), false);
        assertMessage("请先完成实名注册再寄送物资", () -> crowdfundService.registerGoods(ok, guest, parcel()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM donate_shipment WHERE biz_type = 3 AND biz_id IN (?, ?, ?, ?, ?)",
                Integer.class, moneyOnly, draft, ended, notStarted, ok), "被拒的一条运单都不该落");
    }

    @Test
    void goodsOnlyCrowdfund_refusesOnlineMoney() {
        Long cf = open(withRecv(save("只收物-" + next(), false, true)));
        assertMessage("这个项目只接受捐物，不接受捐款",
                () -> donationService.donateToCrowdfund(cf, donor, DonationServiceTest.crowdfund("10", null, false)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM donate_donation WHERE project_id = ? AND biz_type = 2",
                Integer.class, cf));
    }

    // ---------------- helpers ----------------

    private Long open(PairDTOs.CrowdfundSave dto) {
        Long id = crowdfundService.create(dto, ADMIN);
        crowdfundService.publish(id);
        return id;
    }

    private static PairDTOs.CrowdfundSave save(String title, Boolean money, Boolean goods) {
        PairDTOs.CrowdfundSave d = new PairDTOs.CrowdfundSave();
        d.setTitle(title);
        d.setTargetAmount(new BigDecimal("5000"));
        d.setAcceptMoney(money);
        d.setAcceptGoods(goods);
        return d;
    }

    private static PairDTOs.CrowdfundSave withRecv(PairDTOs.CrowdfundSave d) {
        return withRecv(d, x -> { });
    }

    private static PairDTOs.CrowdfundSave withRecv(PairDTOs.CrowdfundSave d, Consumer<PairDTOs.CrowdfundSave> more) {
        d.setGoodsNeeded("书包、文具");
        d.setRecvName("恒德协会物资组");
        d.setRecvPhone("0759-8888888");
        d.setRecvAddress("广东省雷州市某路 1 号");
        more.accept(d);
        return d;
    }

    private static com.hengde.donate.dto.ShipmentRegisterDTO parcel() {
        return shipment(expressNo(), item("书", DonateFlow.TYPE_BOOK, 1, null));
    }

    private static PageQuery page() {
        PageQuery q = new PageQuery();
        q.setPage(1);
        q.setSize(50);
        return q;
    }

    private static void assertMessage(String expected, org.junit.jupiter.api.function.Executable action) {
        assertMessage(expected, action, null);
    }

    private static void assertMessage(String expected, org.junit.jupiter.api.function.Executable action, String why) {
        assertEquals(expected, assertThrows(BusinessException.class, action, why).getMessage(), why);
    }
}
