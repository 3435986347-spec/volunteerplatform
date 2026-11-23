package com.hengde.donate;

import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.service.PointService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.config.MallProperties;
import com.hengde.donate.constant.MallDeliveryType;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.constant.MallOrderStatus;
import com.hengde.donate.constant.MallShippingPayType;
import com.hengde.donate.constant.PickupOperatorType;
import com.hengde.donate.dto.ExpressDTO;
import com.hengde.donate.dto.MallOrderPlaceDTO;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.service.MallOrderService;
import com.hengde.donate.service.MallShippingCalculator;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 积分商城·快递寄送与现金支付（V3 商城快递批）。
 *
 * <p>钉住的是「钱与东西」两本账在每一条路径上都对得上：</p>
 * <ul>
 *   <li>快递费<b>积分抵扣</b>并进同一笔 EXCHANGE 流水，<b>现金</b>并进应付现金；有现金要付的落待支付，积分库存卷照样占住；</li>
 *   <li>付款成功（事件）推进到待审核；<b>事件丢了</b>补偿任务照样推进；<b>超时没付</b>补偿任务取消并全部归还；
 *       <b>超时但渠道其实收到了钱</b>不能取消；</li>
 *   <li>取消待支付的单先关交易单；已付款的志愿者不能自己取消；驳回已付款的单原路退款，退款失败记在单上；</li>
 *   <li>快递单审核通过是「待发货」、没有取货码；发货、确认收货、到期自动确认。</li>
 * </ul>
 *
 * <p>取消与付款的<b>并发赛跑</b>另见 {@code MallExpressConcurrencyTest}。<b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {
        "hengde.donate.mall.express.enabled=true",
        "hengde.donate.mall.express.fee-fen=1250",
        "hengde.donate.mall.express.points-per-yuan=100",
        "hengde.donate.mall.express.auto-receive-days=15",
        "hengde.donate.mall.payment.timeout-minutes=15"})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, MallPaymentFakes.class})
class MallExpressOrderTest {

    private static final AtomicLong SEQ = new AtomicLong(993_000_000L + System.nanoTime() % 1_000_000L);
    private static final long ADMIN = 8801L;
    private static final int PRICE = 30;
    /** 快递费 12.50 元 × 汇率 100 积分/元 = 1250 积分 */
    private static final int SHIPPING_POINTS = 1250;

    @Autowired
    private MallOrderService orderService;
    @Autowired
    private PointService pointService;
    @Autowired
    private TradeOrderService tradeOrderService;
    @Autowired
    private MallProperties mallProperties;
    @Autowired
    private MallPaymentFakes.FakeGateway gateway;
    @Autowired
    private JdbcTemplate jdbc;

    private long volunteer;
    private long specPure;
    private long specCash;

    @BeforeEach
    void setUp() {
        gateway.reset();
        mallProperties.getExpress().setEnabled(true);
        volunteer = SEQ.incrementAndGet();
        specPure = goodsWithSpec("纯积分帆布袋", 0, 5);
        specCash = goodsWithSpec("积分加现金的保温杯", 800, 5);
        givePoints(volunteer, 5000);
    }

    // ================= 下单 =================

    @Test
    void expressWithPointsShipping_mergesShippingIntoOneExchangeRecord() {
        MallOrder order = orderService.placeOrder(volunteer, express(specPure, MallShippingPayType.POINTS));

        assertEquals(MallOrderStatus.PENDING, order.getStatus(), "没有现金要付，直接待审核");
        assertEquals(PRICE + SHIPPING_POINTS, order.getPoints(), "积分抵扣的快递费并进实际扣分");
        assertEquals(SHIPPING_POINTS, order.getShippingPoints());
        assertEquals(1250, order.getShippingFeeFen(), "快递费快照");
        assertEquals(100, order.getPointsPerYuan(), "汇率快照");
        assertEquals(0, order.getPayCashFen());
        assertEquals(5000 - PRICE - SHIPPING_POINTS, pointService.balanceOf(volunteer));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM point_record WHERE volunteer_id = ? AND source_type = ?",
                Integer.class, volunteer, PointSourceType.EXCHANGE), "只有一笔 EXCHANGE 流水——另记一笔会撞 uk_source");

        String stored = jdbc.queryForObject("SELECT recv_phone FROM mall_order WHERE id = ?", String.class, order.getId());
        assertNotEquals("13800000000", stored, "收件电话落库是密文");
        MallOrderVO vo = orderService.detailMine(order.getId(), volunteer);
        assertEquals("13800000000", vo.getRecvPhone(), "本人看到的是明文");
        assertEquals("快递", vo.getDeliveryTypeLabel());
        assertEquals("积分抵扣", vo.getShippingPayTypeLabel());

        // 取消：扣的全部退回，包括快递费那部分
        orderService.cancel(order.getId(), volunteer);
        assertEquals(5000, pointService.balanceOf(volunteer));
        assertEquals(5, stock(specPure));
    }

    @Test
    void cashParts_goAwaitingPayment_holdingStockAndPoints() {
        MallOrder shippingCash = orderService.placeOrder(volunteer, express(specPure, MallShippingPayType.CASH));
        assertEquals(MallOrderStatus.AWAITING_PAYMENT, shippingCash.getStatus());
        assertEquals(1250, shippingCash.getPayCashFen(), "现金付快递费：应付 = 快递费");
        assertEquals(PRICE, shippingCash.getPoints(), "现金付快递费不折积分");
        assertNotNull(shippingCash.getPayExpireTime());

        MallOrder goodsCash = orderService.placeOrder(volunteer, pickup(specCash));
        assertEquals(MallOrderStatus.AWAITING_PAYMENT, goodsCash.getStatus(), "自提也可能要付现金（商品带现金部分）");
        assertEquals(800, goodsCash.getPayCashFen());
        assertEquals(4, stock(specCash), "待支付照样占库存——付款之前不占，同一件能被十个人卡在付款页");
        assertEquals(5000 - PRICE * 2, pointService.balanceOf(volunteer), "待支付照样扣分");

        MallOrder both = orderService.placeOrder(volunteer, express(specCash, MallShippingPayType.CASH));
        assertEquals(800 + 1250, both.getPayCashFen(), "商品现金 + 现金快递费");
        assertEquals("20.50", orderService.detailMine(both.getId(), volunteer).getPayCashYuan());
    }

    @Test
    void cashOrderIsRefusedUpfrontWhenPaymentIsOff_andHoldsNothing() {
        gateway.enabled = false;
        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.placeOrder(volunteer, pickup(specCash)));
        assertTrue(e.getMessage().contains("微信支付尚未开通"), e.getMessage());
        BusinessException e2 = assertThrows(BusinessException.class,
                () -> orderService.placeOrder(volunteer, express(specPure, MallShippingPayType.CASH)));
        assertTrue(e2.getMessage().contains("积分抵扣"), "要告诉他还有别的选法：" + e2.getMessage());

        assertEquals(5, stock(specCash), "付不了的单不能占着库存等超时");
        assertEquals(5000, pointService.balanceOf(volunteer));
        assertEquals(0, orderCount());
        // 积分抵扣快递费照样能下：它不需要商户资质
        assertEquals(MallOrderStatus.PENDING,
                orderService.placeOrder(volunteer, express(specPure, MallShippingPayType.POINTS)).getStatus());
    }

    @Test
    void inputIsValidated_andWrongFieldsAreRefusedNotSilentlyDropped() {
        MallOrderPlaceDTO noPayType = express(specPure, null);
        assertThrows(BusinessException.class, () -> orderService.placeOrder(volunteer, noPayType));
        MallOrderPlaceDTO noAddress = express(specPure, MallShippingPayType.POINTS);
        noAddress.setRecvAddress(" ");
        assertThrows(BusinessException.class, () -> orderService.placeOrder(volunteer, noAddress));
        MallOrderPlaceDTO badPhone = express(specPure, MallShippingPayType.POINTS);
        badPhone.setRecvPhone("12345");
        assertThrows(BusinessException.class, () -> orderService.placeOrder(volunteer, badPhone));
        MallOrderPlaceDTO pickupWithAddress = pickup(specPure);
        pickupWithAddress.setRecvAddress("某路 1 号");
        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.placeOrder(volunteer, pickupWithAddress));
        assertTrue(e.getMessage().contains("自提不需要"), "填了不该填的报错，不静默清空：" + e.getMessage());

        mallProperties.getExpress().setEnabled(false);
        BusinessException off = assertThrows(BusinessException.class,
                () -> orderService.placeOrder(volunteer, express(specPure, MallShippingPayType.POINTS)));
        assertTrue(off.getMessage().contains("暂不支持快递"), off.getMessage());
        assertEquals(0, orderCount(), "校验失败一张单都不该落");
        assertEquals(5000, pointService.balanceOf(volunteer));
    }

    // ================= 付款 =================

    @Test
    void payThenCallback_movesToPendingReview() {
        MallOrder order = orderService.placeOrder(volunteer, pickup(specCash));
        TradeVOs.Prepay prepay = orderService.pay(order.getId(), volunteer, "code-1");
        assertEquals(800, prepay.getAmountFen());
        assertNotNull(prepay.getPrepayId());
        assertFalse(prepay.getExpireTime().isAfter(order.getPayExpireTime()),
                "交易单不能比兑换单活得久：" + prepay.getExpireTime() + " vs " + order.getPayExpireTime());
        assertEquals("openid-code-1", gateway.lastOpenid, "交给渠道的 openid 是服务端用 code 现换的，不信任客户端报上来的");

        TradeVOs.Prepay again = orderService.pay(order.getId(), volunteer, "code-2");
        assertEquals(prepay.getOutTradeNo(), again.getOutTradeNo(), "再点一次付款复用同一张交易单");

        paidCallback(prepay);
        MallOrder paid = load(order.getId());
        assertEquals(MallOrderStatus.PENDING, paid.getStatus(), "付款成功事件把它推到待审核");
        assertNotNull(paid.getPaidTime());
        assertEquals(tradeIdOf(prepay.getOutTradeNo()), paid.getTradeOrderId());
        assertThrows(BusinessException.class, () -> orderService.pay(order.getId(), volunteer, "code-3"),
                "付过的单不再需要付款");
    }

    @Test
    void payIsRefusedForOthersBadCodesAndPastDeadline() {
        MallOrder order = orderService.placeOrder(volunteer, pickup(specCash));
        assertThrows(BusinessException.class, () -> orderService.pay(order.getId(), volunteer + 1, "c"),
                "别人的单与不存在同一句话");
        assertThrows(BusinessException.class, () -> orderService.pay(order.getId(), volunteer, "bad"));
        MallOrder pure = orderService.placeOrder(volunteer, pickup(specPure));
        assertThrows(BusinessException.class, () -> orderService.pay(pure.getId(), volunteer, "c"), "纯积分单无需付款");

        jdbc.update("UPDATE mall_order SET pay_expire_time = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1),
                order.getId());
        BusinessException late = assertThrows(BusinessException.class,
                () -> orderService.pay(order.getId(), volunteer, "c"));
        assertTrue(late.getMessage().contains("付款时限"), late.getMessage());
        assertEquals(0, gateway.prepays.get(), "被拒的付款一次都不该去渠道下单");
    }

    @Test
    void lostPaidEvent_isRecoveredByTheSyncJob() {
        MallOrder order = orderService.placeOrder(volunteer, pickup(specCash));
        TradeVOs.Prepay prepay = orderService.pay(order.getId(), volunteer, "c");
        // 模拟：交易单已经被推成已支付，但发给商城的那次事件丢了（进程在提交之后挂掉）
        jdbc.update("UPDATE trade_order SET status = ?, pay_time = NOW() WHERE out_trade_no = ?",
                TradeFlow.ORDER_PAID, prepay.getOutTradeNo());
        assertEquals(MallOrderStatus.AWAITING_PAYMENT, load(order.getId()).getStatus());

        orderService.syncAwaitingPayments();

        assertEquals(MallOrderStatus.PENDING, load(order.getId()).getStatus(),
                "事件只发一次、不持久——补偿任务必须自己去问 trade");
    }

    @Test
    void timeoutWithoutPayment_isCancelledAndEverythingReturned() {
        MallOrder never = orderService.placeOrder(volunteer, pickup(specCash));
        MallOrder started = orderService.placeOrder(volunteer, pickup(specCash));
        TradeVOs.Prepay prepay = orderService.pay(started.getId(), volunteer, "c");
        expire(never.getId());
        expire(started.getId());
        jdbc.update("UPDATE trade_order SET expire_time = ? WHERE out_trade_no = ?",
                LocalDateTime.now().minusMinutes(1), prepay.getOutTradeNo());

        orderService.syncAwaitingPayments();

        for (Long id : new Long[]{never.getId(), started.getId()}) {
            MallOrder o = load(id);
            assertEquals(MallOrderStatus.CANCELLED, o.getStatus(), "超时没付的取消：" + id);
            assertTrue(o.getRejectReason().contains("付款时限"), o.getRejectReason());
        }
        assertEquals(TradeFlow.ORDER_CLOSED, tradeStatus(prepay.getOutTradeNo()), "交易单也被关掉");
        assertEquals(5, stock(specCash), "库存全部还回");
        assertEquals(5000, pointService.balanceOf(volunteer), "积分全部退回");
    }

    @Test
    void timeoutButTheChannelActuallyGotTheMoney_isNotCancelled() {
        MallOrder order = orderService.placeOrder(volunteer, pickup(specCash));
        TradeVOs.Prepay prepay = orderService.pay(order.getId(), volunteer, "c");
        // 回调丢了：只有渠道知道钱到了；兑换单与交易单都已过期
        gateway.markPaidOnRemote(prepay.getOutTradeNo(), "wx-" + SEQ.incrementAndGet(), 800);
        expire(order.getId());
        jdbc.update("UPDATE trade_order SET expire_time = ? WHERE out_trade_no = ?",
                LocalDateTime.now().minusMinutes(1), prepay.getOutTradeNo());

        orderService.syncAwaitingPayments();

        assertEquals(MallOrderStatus.PENDING, load(order.getId()).getStatus(),
                "先问渠道付了没有，再决定取消——否则就是钱收了、单子没了");
        assertEquals(TradeFlow.ORDER_PAID, tradeStatus(prepay.getOutTradeNo()));
    }

    // ================= 取消与驳回 =================

    @Test
    void cancellingAnAwaitingOrderClosesTheTradeOrderFirst() {
        MallOrder order = orderService.placeOrder(volunteer, pickup(specCash));
        TradeVOs.Prepay prepay = orderService.pay(order.getId(), volunteer, "c");

        orderService.cancel(order.getId(), volunteer);

        assertEquals(MallOrderStatus.CANCELLED, load(order.getId()).getStatus());
        assertEquals(TradeFlow.ORDER_CLOSED, tradeStatus(prepay.getOutTradeNo()));
        assertTrue(gateway.closed.contains(prepay.getOutTradeNo()), "渠道侧也关，否则他仍付得进来");
        assertEquals(5, stock(specCash));
        assertEquals(5000, pointService.balanceOf(volunteer));
    }

    @Test
    void cancelIsRefusedWhenThePaymentJustLanded() {
        MallOrder order = orderService.placeOrder(volunteer, pickup(specCash));
        TradeVOs.Prepay prepay = orderService.pay(order.getId(), volunteer, "c");
        // 交易单刚被推成已支付、商城这边还没收到事件（那一瞬间）
        jdbc.update("UPDATE trade_order SET status = ? WHERE out_trade_no = ?", TradeFlow.ORDER_PAID,
                prepay.getOutTradeNo());

        BusinessException e = assertThrows(BusinessException.class, () -> orderService.cancel(order.getId(), volunteer));
        assertTrue(e.getMessage().contains("已付款"), e.getMessage());
        assertEquals(MallOrderStatus.AWAITING_PAYMENT, load(order.getId()).getStatus(), "取消必须放弃，什么都不动");
        assertEquals(4, stock(specCash));
    }

    @Test
    void volunteerCannotCancelAPaidOrder() {
        MallOrder order = paidOrder();
        BusinessException e = assertThrows(BusinessException.class, () -> orderService.cancel(order.getId(), volunteer));
        assertTrue(e.getMessage().contains("联系协会"), e.getMessage());
        assertEquals(MallOrderStatus.PENDING, load(order.getId()).getStatus());
    }

    @Test
    void rejectingAPaidOrder_refundsCashAfterTheRejectCommits() {
        MallOrder order = paidOrder();
        orderService.reject(order.getId(), "规格缺货", ADMIN);

        MallOrder rejected = load(order.getId());
        assertEquals(MallOrderStatus.REJECTED, rejected.getStatus());
        assertNotNull(rejected.getCashRefundNo(), "原路退款已受理，单号记在兑换单上");
        assertNull(rejected.getCashRefundError());
        assertEquals(1, gateway.refunds.size());
        assertEquals(TradeFlow.ORDER_REFUNDED, tradeStatus(outTradeNoOf(rejected.getTradeOrderId())));
        assertEquals(5000, pointService.balanceOf(volunteer), "积分同样退回");
        assertEquals(5, stock(specCash));
    }

    @Test
    void refundFailure_doesNotUndoTheReject_butIsRecordedOnTheOrder() {
        MallOrder order = paidOrder();
        gateway.refundFails = true;

        orderService.reject(order.getId(), "规格缺货", ADMIN);

        MallOrder rejected = load(order.getId());
        assertEquals(MallOrderStatus.REJECTED, rejected.getStatus(), "退款失败不回滚驳回");
        assertNull(rejected.getCashRefundNo());
        assertNotNull(rejected.getCashRefundError(), "钱还没退出去这件事必须看得见——落在单上，后台据此到收付页重试");
        assertEquals(5000, pointService.balanceOf(volunteer));
        MallOrderVO adminVo = orderService.listForAdmin(pageOf(), MallOrderStatus.REJECTED, rejected.getOrderNo())
                .getRecords().get(0);
        assertEquals(rejected.getTradeOrderId(), adminVo.getTradeOrderId());
        assertNotNull(adminVo.getCashRefundError());
    }

    @Test
    void rejectingAnAwaitingOrder_closesTheTradeOrder_andRefundsNoCash() {
        MallOrder order = orderService.placeOrder(volunteer, pickup(specCash));
        TradeVOs.Prepay prepay = orderService.pay(order.getId(), volunteer, "c");
        orderService.reject(order.getId(), "不符合兑换规则", ADMIN);

        assertEquals(MallOrderStatus.REJECTED, load(order.getId()).getStatus());
        assertEquals(TradeFlow.ORDER_CLOSED, tradeStatus(prepay.getOutTradeNo()));
        assertEquals(0, gateway.refunds.size(), "没付过的单没有钱可退");
    }

    // ================= 发货与收货 =================

    @Test
    void expressOrderIsApprovedToAwaitShipping_thenShippedAndReceived() {
        MallOrder order = orderService.placeOrder(volunteer, express(specPure, MallShippingPayType.POINTS));
        orderService.approve(order.getId(), ADMIN);

        MallOrder approved = load(order.getId());
        assertEquals(MallOrderStatus.READY, approved.getStatus());
        assertNull(approved.getPickupCode(), "快递单没有取货码");
        assertEquals("待发货", orderService.detailMine(order.getId(), volunteer).getStatusLabel());

        assertThrows(BusinessException.class, () -> orderService.confirmReceipt(order.getId(), volunteer), "没发货不能确认收货");
        orderService.ship(order.getId(), expressDto("shunfeng", " sf 123 456 "), ADMIN);
        MallOrderVO shipped = orderService.detailMine(order.getId(), volunteer);
        assertEquals("已发货", shipped.getStatusLabel());
        assertEquals("SF123456", shipped.getExpressNo(), "单号规整：去空白转大写");
        assertThrows(BusinessException.class,
                () -> orderService.ship(order.getId(), expressDto("shunfeng", "SF999"), ADMIN), "发货一次性");

        assertThrows(BusinessException.class, () -> orderService.confirmReceipt(order.getId(), volunteer + 1));
        orderService.confirmReceipt(order.getId(), volunteer);
        MallOrder received = load(order.getId());
        assertEquals(MallOrderStatus.PICKED, received.getStatus(), "签收落已领取——评价资格不用改");
        assertEquals(PickupOperatorType.RECIPIENT, received.getPickupOperatorType());
        assertEquals("已签收", orderService.detailMine(order.getId(), volunteer).getStatusLabel());
    }

    @Test
    void pickupOrdersCannotBeShipped_andOldShipmentsAreAutoReceived() {
        MallOrder pickup = orderService.placeOrder(volunteer, pickup(specPure));
        orderService.approve(pickup.getId(), ADMIN);
        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.ship(pickup.getId(), expressDto("shunfeng", "SF1"), ADMIN));
        assertTrue(e.getMessage().contains("自提"), e.getMessage());

        MallOrder old = orderService.placeOrder(volunteer, express(specPure, MallShippingPayType.POINTS));
        MallOrder fresh = orderService.placeOrder(volunteer, express(specPure, MallShippingPayType.POINTS));
        for (MallOrder o : new MallOrder[]{old, fresh}) {
            orderService.approve(o.getId(), ADMIN);
            orderService.ship(o.getId(), expressDto("yuantong", "YT" + SEQ.incrementAndGet()), ADMIN);
        }
        jdbc.update("UPDATE mall_order SET ship_time = ? WHERE id = ?", LocalDateTime.now().minusDays(16), old.getId());

        orderService.autoReceive();

        assertEquals(MallOrderStatus.PICKED, load(old.getId()).getStatus(), "发货满 15 天自动确认");
        assertEquals(PickupOperatorType.SYSTEM, load(old.getId()).getPickupOperatorType());
        assertEquals(MallOrderStatus.SHIPPED, load(fresh.getId()).getStatus(), "没到期的不动");
    }

    @Test
    void shippingPointsRoundUp() {
        assertEquals(0, MallShippingCalculator.pointsFor(0, 100));
        assertEquals(1, MallShippingCalculator.pointsFor(1, 100), "差一分钱也要折一分，否则白送");
        assertEquals(150, MallShippingCalculator.pointsFor(150, 100), "1.5 元 × 100 = 150");
        assertEquals(2, MallShippingCalculator.pointsFor(150, 1), "1.5 元 × 1 = 1.5，向上取整为 2");
        assertEquals(13, MallShippingCalculator.pointsFor(1250, 1));
    }

    // ---------------- helpers ----------------

    private MallOrder paidOrder() {
        MallOrder order = orderService.placeOrder(volunteer, pickup(specCash));
        paidCallback(orderService.pay(order.getId(), volunteer, "c"));
        assertEquals(MallOrderStatus.PENDING, load(order.getId()).getStatus());
        return load(order.getId());
    }

    private void paidCallback(TradeVOs.Prepay prepay) {
        assertTrue(tradeOrderService.applyPaidResult(prepay.getOutTradeNo(), "wx-" + SEQ.incrementAndGet(),
                prepay.getAmountFen(), LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK));
    }

    static MallOrderPlaceDTO pickup(long specId) {
        MallOrderPlaceDTO d = new MallOrderPlaceDTO();
        d.setSpecId(specId);
        d.setDeliveryType(MallDeliveryType.PICKUP);
        return d;
    }

    static MallOrderPlaceDTO express(long specId, Integer payType) {
        MallOrderPlaceDTO d = new MallOrderPlaceDTO();
        d.setSpecId(specId);
        d.setDeliveryType(MallDeliveryType.EXPRESS);
        d.setShippingPayType(payType);
        d.setRecvName("收件人");
        d.setRecvPhone("13800000000");
        d.setRecvAddress("广东省雷州市某路 1 号");
        return d;
    }

    private static ExpressDTO expressDto(String code, String no) {
        ExpressDTO d = new ExpressDTO();
        d.setExpressCode(code);
        d.setExpressNo(no);
        return d;
    }

    private static PageQuery pageOf() {
        PageQuery q = new PageQuery();
        q.setPage(1);
        q.setSize(20);
        return q;
    }

    private long goodsWithSpec(String name, int cashFen, int stock) {
        long goodsId = SEQ.incrementAndGet();
        long specId = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO mall_goods (id, name, status, hidden, sort, create_time, update_time, is_deleted) "
                + "VALUES (?, ?, ?, 0, 0, NOW(), NOW(), 0)", goodsId, name, MallGoodsStatus.ON_SALE);
        jdbc.update("INSERT INTO mall_goods_spec (id, goods_id, name, points, cash_fen, stock, sort, create_time, "
                + "update_time, is_deleted) VALUES (?, ?, '默认', ?, ?, ?, 0, NOW(), NOW(), 0)",
                specId, goodsId, PRICE, cashFen, stock);
        return specId;
    }

    private void givePoints(long volunteerId, int amount) {
        jdbc.update("INSERT INTO point_record (volunteer_id, change_amount, source_type, source_id, remark, "
                + "operator_type, create_time, update_time, is_deleted) VALUES (?, ?, ?, NULL, '用例预置', 0, NOW(), NOW(), 0)",
                volunteerId, amount, PointSourceType.MANUAL);
    }

    private void expire(Long orderId) {
        jdbc.update("UPDATE mall_order SET pay_expire_time = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1),
                orderId);
    }

    private int stock(long specId) {
        return jdbc.queryForObject("SELECT stock FROM mall_goods_spec WHERE id = ?", Integer.class, specId);
    }

    private int orderCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM mall_order WHERE volunteer_id = ?", Integer.class, volunteer);
    }

    private MallOrder load(Long id) {
        return jdbc.queryForObject("SELECT id, order_no, status, points, trade_order_id, paid_time, pickup_code, "
                + "pickup_operator_type, reject_reason, cash_refund_no, cash_refund_error, pay_expire_time "
                + "FROM mall_order WHERE id = ?", (rs, i) -> {
                    MallOrder o = new MallOrder();
                    o.setId(rs.getLong("id"));
                    o.setOrderNo(rs.getString("order_no"));
                    o.setStatus(rs.getInt("status"));
                    o.setPoints(rs.getInt("points"));
                    long t = rs.getLong("trade_order_id");
                    o.setTradeOrderId(rs.wasNull() ? null : t);
                    o.setPaidTime(rs.getObject("paid_time", LocalDateTime.class));
                    o.setPickupCode(rs.getString("pickup_code"));
                    int op = rs.getInt("pickup_operator_type");
                    o.setPickupOperatorType(rs.wasNull() ? null : op);
                    o.setRejectReason(rs.getString("reject_reason"));
                    o.setCashRefundNo(rs.getString("cash_refund_no"));
                    o.setCashRefundError(rs.getString("cash_refund_error"));
                    o.setPayExpireTime(rs.getObject("pay_expire_time", LocalDateTime.class));
                    return o;
                }, id);
    }

    private int tradeStatus(String outTradeNo) {
        return jdbc.queryForObject("SELECT status FROM trade_order WHERE out_trade_no = ?", Integer.class, outTradeNo);
    }

    private Long tradeIdOf(String outTradeNo) {
        return jdbc.queryForObject("SELECT id FROM trade_order WHERE out_trade_no = ?", Long.class, outTradeNo);
    }

    private String outTradeNoOf(Long tradeOrderId) {
        return jdbc.queryForObject("SELECT out_trade_no FROM trade_order WHERE id = ?", String.class, tradeOrderId);
    }
}
