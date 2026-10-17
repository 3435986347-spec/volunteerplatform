package com.hengde.donate;

import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.service.PointService;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RecordingSmsConfig;
import com.hengde.common.testsupport.RecordingSmsConfig.RecordingSmsService;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.MallDeliveryType;
import com.hengde.donate.constant.MallOrderStatus;
import com.hengde.donate.constant.MallShippingPayType;
import com.hengde.donate.dto.ExpressDTO;
import com.hengde.donate.dto.MallGoodsSaveDTO;
import com.hengde.donate.dto.MallGoodsSpecDTO;
import com.hengde.donate.dto.MallOrderPlaceDTO;
import com.hengde.donate.dto.MallPickupPointDTO;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.service.MallGoodsService;
import com.hengde.donate.service.MallOrderService;
import com.hengde.donate.service.MallPickupPointService;
import com.hengde.donate.vo.MallGoodsVO;
import com.hengde.donate.vo.MallOrderVO;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.service.TradeOrderService;
import com.hengde.trade.vo.TradeVOs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协会答复五（2026-10-16）对积分商城的三条口径——<b>本类显式关掉兑换审核</b>，测的就是新的默认行为：
 *
 * <ul>
 *   <li><b>乙-5</b>：纯积分兑换「点击兑换之后则进入成功状态」，有现金的付款即成功；志愿者不能取消成功的单，
 *       「取消需要协会操作」——退分、还库存、还卷、原路退款、短信告知；</li>
 *   <li><b>丙-3</b>：领取方式是商品级设置（自提 / 快递 / 都行），自提可选自提点，快递设快递费或运费积分，
 *       虚拟商品只能自提、详情给兑换二维码；</li>
 *   <li><b>丁-2</b>：规格可以只收积分、只收金额，或两者都收。</li>
 * </ul>
 *
 * <p>改造前的审核流程由别的用例在 {@code order-review=true}（测试 classpath 上的 application.yaml）下照常覆盖。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {
        "hengde.donate.mall.order-review=false",
        "hengde.donate.mall.express.enabled=false",
        "hengde.donate.mall.pickup-site.name=协会办公室（配置）",
        "hengde.sms.templates.points-order-review=T-MALL-RESULT"})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, RecordingSmsConfig.class, MallPaymentFakes.class})
class MallImmediateOrderTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);
    private static final long ADMIN = 9901L;

    @Autowired
    private MallOrderService orderService;
    @Autowired
    private MallGoodsService goodsService;
    @Autowired
    private MallPickupPointService pickupPointService;
    @Autowired
    private PointService pointService;
    @Autowired
    private TradeOrderService tradeOrderService;
    @Autowired
    private MallPaymentFakes.FakeGateway gateway;
    @Autowired
    private RecordingSmsService sms;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    private long me;
    private long office;
    private long school;

    @BeforeEach
    void setUp() {
        gateway.reset();
        sms.clear();
        me = volunteer("1390000" + String.format("%04d", SEQ.incrementAndGet() % 10_000));
        givePoints(me, 1000);
        office = pickupPointService.create(point("协会办公室-" + SEQ.incrementAndGet(), "雷州市新城大道 1 号"));
        school = pickupPointService.create(point("附城小学-" + SEQ.incrementAndGet(), "雷州市附城镇"));
    }

    // ================= 乙-5：兑换即成功；志愿者不能取消，协会取消 =================

    @Test
    void pointsOnlyPickup_succeedsAtOnce_atTheChosenPoint_andOnlyTheAssociationCanCancel() {
        long spec = goods(pickupOnly(office, school), spec("标准", 30, 0, 5));

        MallOrder order = orderService.placeOrder(me, pickupAt(spec, school));

        assertEquals(MallOrderStatus.READY, order.getStatus(), "纯积分兑换：点了就是成功，不等审核");
        assertNotNull(order.getPickupCode(), "成功那一刻就有取货码");
        assertEquals(school, order.getPickupPointId());
        assertTrue(order.getPickupSiteName().startsWith("附城小学"), "领取地点是他选的那个，快照进单子");
        assertEquals("雷州市附城镇", order.getPickupSiteAddr());
        assertEquals(970, pointService.balanceOf(me));
        assertEquals(4, stock(spec));
        assertTrue(sms.all().isEmpty(), "自己刚点完的事不发短信");

        BusinessException self = assertThrows(BusinessException.class, () -> orderService.cancel(order.getId(), me));
        assertTrue(self.getMessage().contains("联系协会"), self.getMessage());

        orderService.cancelByAdmin(order.getId(), "该商品暂缺货", ADMIN);
        MallOrder after = load(order.getId());
        assertEquals(MallOrderStatus.CANCELLED, after.getStatus());
        assertEquals("该商品暂缺货", after.getRejectReason());
        assertEquals(1000, pointService.balanceOf(me), "积分退回");
        assertEquals(5, stock(spec), "库存还回");
        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-MALL-RESULT");
        assertEquals(1, sent.size(), "协会取消要告诉他");
        assertEquals("已取消", sent.get(0).params().get("status"));
        assertTrue(sent.get(0).params().get("remark").contains("该商品暂缺货"));
        assertTrue(sent.get(0).params().get("remark").contains("积分"));

        assertThrows(BusinessException.class, () -> orderService.cancelByAdmin(order.getId(), "再取消一次", ADMIN),
                "已取消的不能再取消");
    }

    @Test
    void pickupPoint_mustBeChosenFromTheGoodsList_andStillEnabled() {
        long spec = goods(pickupOnly(office), spec("标准", 10, 0, 5));
        assertTrue(assertThrows(BusinessException.class, () -> orderService.placeOrder(me, pickupAt(spec, null)))
                .getMessage().contains("请选择自提点"));
        assertTrue(assertThrows(BusinessException.class, () -> orderService.placeOrder(me, pickupAt(spec, school)))
                .getMessage().contains("不能在这个自提点"));
        MallPickupPointDTO off = point("协会办公室（停用）", "雷州市新城大道 1 号");
        off.setEnabled(0);
        pickupPointService.update(office, off);
        assertTrue(assertThrows(BusinessException.class, () -> orderService.placeOrder(me, pickupAt(spec, office)))
                .getMessage().contains("已停用"));
        assertEquals(5, stock(spec), "被拒的下单不占库存");
        assertEquals(1000, pointService.balanceOf(me));
    }

    @Test
    void goodsWithoutListedPoints_useTheConfiguredSite() {
        long spec = goods(pickupOnly(), spec("标准", 10, 0, 5));
        MallOrder order = orderService.placeOrder(me, pickupAt(spec, null));
        assertEquals(MallOrderStatus.READY, order.getStatus());
        assertEquals("协会办公室（配置）", order.getPickupSiteName());
        assertNull(order.getPickupPointId());
        assertTrue(assertThrows(BusinessException.class, () -> orderService.placeOrder(me, pickupAt(spec, office)))
                .getMessage().contains("没有可选的自提点"));
    }

    @Test
    void legacyGoods_withoutDeliverySettings_stillWork() {
        long spec = goods(null, spec("标准", 10, 0, 5));
        MallGoodsVO vo = goodsService.detailForVolunteer(goodsIdOf(spec));
        assertTrue(vo.getAllowPickup());
        assertFalse(vo.getAllowExpress(), "没设置过的商品跟全局快递开关（本类关着）");
        assertFalse(vo.getDeliveryConfigured());
        MallOrder order = orderService.placeOrder(me, pickupAt(spec, null));
        assertEquals(MallOrderStatus.READY, order.getStatus());
        assertThrows(BusinessException.class, () -> orderService.placeOrder(me, express(spec, MallShippingPayType.POINTS)),
                "存量商品的快递仍受全局开关管");
    }

    @Test
    void cashOrder_succeedsWhenPaid_andAssociationCancelRefundsTheCashToo() {
        long spec = goods(pickupOnly(), spec("标准", 20, 800, 5));
        MallOrder order = orderService.placeOrder(me, pickupAt(spec, null));
        assertEquals(MallOrderStatus.AWAITING_PAYMENT, order.getStatus());

        TradeVOs.Prepay prepay = orderService.pay(order.getId(), me, "code");
        assertTrue(tradeOrderService.applyPaidResult(prepay.getOutTradeNo(), "wx-" + SEQ.incrementAndGet(),
                prepay.getAmountFen(), LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK));
        MallOrder paid = load(order.getId());
        assertEquals(MallOrderStatus.READY, paid.getStatus(), "付款即成功，不等审核");
        assertNotNull(paid.getPickupCode());

        assertTrue(assertThrows(BusinessException.class, () -> orderService.cancel(order.getId(), me))
                .getMessage().contains("联系协会"));
        orderService.cancelByAdmin(order.getId(), "协会取消", ADMIN);
        MallOrder cancelled = load(order.getId());
        assertEquals(MallOrderStatus.CANCELLED, cancelled.getStatus());
        assertNotNull(cancelled.getCashRefundNo(), "付过的现金原路退回");
        assertNull(cancelled.getCashRefundError());
        assertEquals(1000, pointService.balanceOf(me));
    }

    @Test
    void unpaidOrder_canStillBeCancelledByTheVolunteer() {
        long spec = goods(pickupOnly(), spec("标准", 20, 800, 5));
        MallOrder order = orderService.placeOrder(me, pickupAt(spec, null));
        orderService.cancel(order.getId(), me);
        assertEquals(MallOrderStatus.CANCELLED, load(order.getId()).getStatus(), "未付款的可以自行取消");
        assertEquals(1000, pointService.balanceOf(me));
    }

    // ================= 丙-3：快递费 / 运费积分按商品设 =================

    @Test
    void express_feeOrPointsComeFromTheGoods_notTheGlobalSwitch() {
        MallGoodsSaveDTO d = delivery(0, 1, null, null, 50);
        long spec = goods(d, spec("标准", 30, 0, 5));

        MallOrder order = orderService.placeOrder(me, express(spec, MallShippingPayType.POINTS));
        assertEquals(MallOrderStatus.READY, order.getStatus(), "积分付运费也是纯积分：兑换即成功（待发货）");
        assertEquals(80, order.getPoints(), "商品 30 + 运费积分 50");
        assertEquals(50, order.getShippingPoints());
        assertNull(order.getPickupCode(), "快递单没有取货码");
        assertEquals("待发货", MallOrderStatus.labelOf(order.getStatus(), order.getDeliveryType()));

        assertTrue(assertThrows(BusinessException.class,
                () -> orderService.placeOrder(me, express(spec, MallShippingPayType.CASH))).getMessage().contains("只能用积分"));
        assertTrue(assertThrows(BusinessException.class,
                () -> orderService.placeOrder(me, pickupAt(spec, null))).getMessage().contains("不支持自提"));

        orderService.ship(order.getId(), expressNo(), ADMIN);
        assertEquals(MallOrderStatus.SHIPPED, load(order.getId()).getStatus());
        assertThrows(BusinessException.class, () -> orderService.cancelByAdmin(order.getId(), "已发货还能取消吗", ADMIN),
                "东西已经寄出去的不能取消");
    }

    @Test
    void goodsDeliverySettings_areValidatedWhenSaved() {
        assertTrue(save(delivery(0, 0, null, null, null)).contains("至少选择一种"));
        assertTrue(save(delivery(0, 1, null, null, null)).contains("快递费或运费积分"));
        assertTrue(save(delivery(1, 0, null, 100, null)).contains("不用设置快递费"));
        MallGoodsSaveDTO badPoint = delivery(1, 0, List.of(999_999_999L), null, null);
        assertTrue(save(badPoint).contains("不存在或已停用"));
        MallGoodsSaveDTO noSwitch = base();
        noSwitch.setExpressPoints(10);
        assertTrue(save(noSwitch).contains("请选择领取方式"), "设了快递费却没选领取方式——静默丢掉会让它看起来保存成功");
    }

    // ================= 丙-3：虚拟商品 =================

    @Test
    void virtualGoods_pickupOnly_withAQrFromOurOwnUpload() {
        String qr = "[oss-disabled]/" + MallGoodsService.ADMIN_IMAGE_DIR + "/20261016/" + "a".repeat(32) + ".png";
        MallGoodsSaveDTO v = delivery(1, 0, null, null, null);
        v.setVirtualGoods(1);
        v.setRedeemQrUrl(qr);
        long spec = goods(v, spec("会员资格", 100, 0, 50));

        MallGoodsVO vo = goodsService.detailForVolunteer(goodsIdOf(spec));
        assertTrue(vo.getVirtualGoods());
        assertNull(vo.getRedeemQrUrl(), "公开的商品页上不挂兑换二维码——挂着就不必兑换了");
        assertEquals(qr, goodsService.detailForAdmin(goodsIdOf(spec)).getRedeemQrUrl(), "后台编辑回填看得到");
        MallOrder order = orderService.placeOrder(me, pickupAt(spec, null));
        assertEquals(MallOrderStatus.READY, order.getStatus());
        MallOrderVO mine = orderService.detailMine(order.getId(), me);
        assertTrue(mine.getVirtualGoods());
        assertEquals(qr, mine.getRedeemQrUrl(), "兑换成功后，本人的兑换详情里给兑换二维码（协会 10-16 丙-3）");

        MallGoodsSaveDTO express = delivery(1, 1, null, null, 10);
        express.setVirtualGoods(1);
        express.setRedeemQrUrl(qr);
        assertTrue(save(express).contains("只能设置自提"));
        MallGoodsSaveDTO outside = delivery(1, 0, null, null, null);
        outside.setVirtualGoods(1);
        outside.setRedeemQrUrl("https://evil.example/qr.png");
        assertTrue(save(outside).contains("上传接口"), "外链放进详情页，扫出来的是别人的码");
        MallGoodsSaveDTO missing = delivery(1, 0, null, null, null);
        missing.setVirtualGoods(1);
        assertTrue(save(missing).contains("兑换二维码"));
    }

    // ================= 丁-2：纯现金 / 纯积分 / 两者都收 =================

    @Test
    void cashOnlySpec_costsNoPoints_andCannotTakeACoupon() {
        long spec = goods(pickupOnly(), spec("纯现金", 0, 500, 5));
        MallOrder order = orderService.placeOrder(me, pickupAt(spec, null));
        assertEquals(MallOrderStatus.AWAITING_PAYMENT, order.getStatus());
        assertEquals(0, order.getPoints());
        assertEquals(500, order.getPayCashFen());
        assertEquals(1000, pointService.balanceOf(me), "纯现金不动积分");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM point_record WHERE source_type = ? AND source_id = ?",
                Integer.class, PointSourceType.EXCHANGE, order.getId()), "账本不收 0 分的流水");

        MallOrderPlaceDTO withCoupon = pickupAt(spec, null);
        withCoupon.setCouponGrantId(12345L);
        assertTrue(assertThrows(BusinessException.class, () -> orderService.placeOrder(me, withCoupon))
                .getMessage().contains("不能用卷"));
        assertTrue(save(pickupOnly(), spec("什么都不收", 0, 0, 1)).contains("至少填写所需积分或金额"));
    }

    // ================= 协会取消 与 现场核销 抢同一张单 =================

    /**
     * 协会取消与柜台核销都是「待领取 → 别的状态」的 CAS，只能成一个；成的是取消就全部退回，成的是核销就一分不退。
     * 偏置起跑三分之一先取消、三分之一先核销、三分之一同时，断言两种结局都真的出现过（不偏置时一方几乎总赢，另一条分支跑不到）。
     */
    @Test
    void associationCancel_racesPickup_exactlyOneWins() throws Exception {
        long spec = goods(pickupOnly(), spec("标准", 10, 0, 200));
        givePoints(me, 2000);
        int cancelWins = 0;
        int pickupWins = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 30; round++) {
                MallOrder order = orderService.placeOrder(me, pickupAt(spec, null));
                int before = pointService.balanceOf(me);
                int stockBefore = stock(spec);
                int bias = round % 3;
                CountDownLatch go = new CountDownLatch(1);
                Future<Boolean> cancel = pool.submit(() -> {
                    go.await();
                    if (bias == 1) {
                        Thread.sleep(30);
                    }
                    try {
                        orderService.cancelByAdmin(order.getId(), "抢跑", ADMIN);
                        return true;
                    } catch (BusinessException e) {
                        return false;
                    }
                });
                Future<Boolean> pickup = pool.submit(() -> {
                    go.await();
                    if (bias == 0) {
                        Thread.sleep(30);
                    }
                    try {
                        orderService.verify(order.getPickupCode(), ADMIN);
                        return true;
                    } catch (BusinessException e) {
                        return false;
                    }
                });
                go.countDown();
                boolean c = cancel.get();
                boolean p = pickup.get();
                assertTrue(c ^ p, "第 " + round + " 轮：取消与核销必须恰好成一个（取消 " + c + " / 核销 " + p + "）");
                MallOrder after = load(order.getId());
                if (c) {
                    cancelWins++;
                    assertEquals(MallOrderStatus.CANCELLED, after.getStatus());
                    assertEquals(before + 10, pointService.balanceOf(me));
                    assertEquals(stockBefore + 1, stock(spec));
                } else {
                    pickupWins++;
                    assertEquals(MallOrderStatus.PICKED, after.getStatus());
                    assertEquals(before, pointService.balanceOf(me), "已领取的一分不退");
                    assertEquals(stockBefore, stock(spec));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(cancelWins > 0 && pickupWins > 0, "两种结局都要出现过：取消 " + cancelWins + " / 核销 " + pickupWins);
    }

    // ---------------- helpers ----------------

    private String save(MallGoodsSaveDTO dto) {
        if (dto.getSpecs() == null) {
            dto.setSpecs(List.of(spec("标准", 10, 0, 1)));
        }
        return assertThrows(BusinessException.class, () -> goodsService.create(dto, ADMIN)).getMessage();
    }

    private String save(MallGoodsSaveDTO dto, MallGoodsSpecDTO spec) {
        dto.setSpecs(List.of(spec));
        return save(dto);
    }

    /** 建一件已上架的商品，返回它唯一规格的 id。 */
    private long goods(MallGoodsSaveDTO settings, MallGoodsSpecDTO spec) {
        MallGoodsSaveDTO dto = settings == null ? base() : settings;
        dto.setSpecs(List.of(spec));
        long id = goodsService.create(dto, ADMIN);
        goodsService.submit(id);
        goodsService.approve(id, ADMIN);
        return jdbc.queryForObject("SELECT id FROM mall_goods_spec WHERE goods_id = ? AND is_deleted = 0", Long.class, id);
    }

    private long goodsIdOf(long specId) {
        return jdbc.queryForObject("SELECT goods_id FROM mall_goods_spec WHERE id = ?", Long.class, specId);
    }

    private static MallGoodsSaveDTO base() {
        MallGoodsSaveDTO d = new MallGoodsSaveDTO();
        d.setName("测试商品-" + SEQ.incrementAndGet());
        return d;
    }

    private static MallGoodsSaveDTO delivery(int pickup, int express, List<Long> points, Integer feeFen, Integer shippingPoints) {
        MallGoodsSaveDTO d = base();
        d.setAllowPickup(pickup);
        d.setAllowExpress(express);
        d.setPickupPointIds(points);
        d.setExpressFeeFen(feeFen);
        d.setExpressPoints(shippingPoints);
        return d;
    }

    private static MallGoodsSaveDTO pickupOnly(Long... points) {
        return delivery(1, 0, points.length == 0 ? null : List.of(points), null, null);
    }

    private static MallGoodsSpecDTO spec(String name, int points, int cashFen, int stock) {
        MallGoodsSpecDTO s = new MallGoodsSpecDTO();
        s.setName(name);
        s.setPoints(points);
        s.setCashFen(cashFen);
        s.setStock(stock);
        return s;
    }

    private static MallPickupPointDTO point(String name, String address) {
        MallPickupPointDTO p = new MallPickupPointDTO();
        p.setName(name);
        p.setAddress(address);
        p.setPhone("0759-1234567");
        return p;
    }

    private static MallOrderPlaceDTO pickupAt(long specId, Long pointId) {
        MallOrderPlaceDTO d = new MallOrderPlaceDTO();
        d.setSpecId(specId);
        d.setDeliveryType(MallDeliveryType.PICKUP);
        d.setPickupPointId(pointId);
        return d;
    }

    private static MallOrderPlaceDTO express(long specId, Integer payType) {
        MallOrderPlaceDTO d = new MallOrderPlaceDTO();
        d.setSpecId(specId);
        d.setDeliveryType(MallDeliveryType.EXPRESS);
        d.setShippingPayType(payType);
        d.setRecvName("收件人");
        d.setRecvPhone("13800000000");
        d.setRecvAddress("广东省雷州市某路 1 号");
        return d;
    }

    private static ExpressDTO expressNo() {
        ExpressDTO d = new ExpressDTO();
        d.setExpressCode("shunfeng");
        d.setExpressNo("SF" + SEQ.incrementAndGet() + "00");
        return d;
    }

    private long volunteer(String phone) {
        Volunteer v = new Volunteer();
        v.setOpenid("immediate_" + System.nanoTime());
        v.setRealName("兑换人");
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private void givePoints(long volunteerId, int amount) {
        jdbc.update("INSERT INTO point_record (volunteer_id, change_amount, source_type, source_id, remark, "
                + "operator_type, create_time, update_time, is_deleted) VALUES (?, ?, ?, NULL, '用例预置', 0, NOW(), NOW(), 0)",
                volunteerId, amount, PointSourceType.MANUAL);
    }

    private int stock(long specId) {
        return jdbc.queryForObject("SELECT stock FROM mall_goods_spec WHERE id = ?", Integer.class, specId);
    }

    private MallOrder load(Long id) {
        return jdbc.queryForObject("SELECT id, status, points, pickup_code, reject_reason, cash_refund_no, "
                + "cash_refund_error FROM mall_order WHERE id = ?", (rs, i) -> {
                    MallOrder o = new MallOrder();
                    o.setId(rs.getLong("id"));
                    o.setStatus(rs.getInt("status"));
                    o.setPoints(rs.getInt("points"));
                    o.setPickupCode(rs.getString("pickup_code"));
                    o.setRejectReason(rs.getString("reject_reason"));
                    o.setCashRefundNo(rs.getString("cash_refund_no"));
                    o.setCashRefundError(rs.getString("cash_refund_error"));
                    return o;
                }, id);
    }
}
