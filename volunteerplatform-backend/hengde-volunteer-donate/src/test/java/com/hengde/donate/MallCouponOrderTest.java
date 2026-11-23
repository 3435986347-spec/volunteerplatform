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
import com.hengde.donate.constant.MallCouponGrantStatus;
import com.hengde.donate.constant.MallCouponType;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.constant.MallOrderStatus;
import com.hengde.donate.dto.MallCouponGrantDTO;
import com.hengde.donate.dto.MallCouponSaveDTO;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.service.MallCouponService;
import com.hengde.donate.service.MallOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用卷下单 / 退单 / 驳回，与审核结果短信（V3 卷批）。
 *
 * <p>每条用例都同时看<b>四本账</b>：订单、库存、卷、积分账本——只看其中一本的用例，
 * 在「退了分却没还卷」这类缺陷上永远是绿的。</p>
 *
 * <p><b>短信必须真的断言内容</b>：{@code SmsNotifyService} 的纪律是「通知失败绝不影响业务」，
 * 参数写错在线上只留一行 ERROR，只断言「审核成功」看不出来（CLAUDE.md 通知短信那一条）。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = "hengde.sms.templates.points-order-review=T-MALL-REVIEW")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, RecordingSmsConfig.class})
class MallCouponOrderTest {

    private static final long ADMIN = 7101L;
    private static final long GOODS_ID = 994_001L;
    private static final long SPEC_ID = 994_101L;
    /** 同一商品的低价规格，够不着满减门槛 */
    private static final long CHEAP_SPEC_ID = 994_102L;
    private static final int PRICE = 30;
    private static final int STOCK = 5;
    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime() % 10_000_000L + 20_000_000L);

    @Autowired
    private MallOrderService orderService;
    @Autowired
    private MallCouponService couponService;
    @Autowired
    private PointService pointService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private RecordingSmsService sms;

    private Long volunteer;
    private String phone;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM mall_goods_spec WHERE goods_id = ?", GOODS_ID);
        jdbc.update("DELETE FROM mall_goods WHERE id = ?", GOODS_ID);
        jdbc.update("INSERT INTO mall_goods (id, name, status, hidden, sort, create_time, update_time, is_deleted) "
                + "VALUES (?, '保温杯', ?, 0, 0, NOW(), NOW(), 0)", GOODS_ID, MallGoodsStatus.ON_SALE);
        jdbc.update("INSERT INTO mall_goods_spec (id, goods_id, name, points, stock, sort, create_time, update_time, "
                + "is_deleted) VALUES (?, ?, '500ml', ?, ?, 0, NOW(), NOW(), 0)", SPEC_ID, GOODS_ID, PRICE, STOCK);
        jdbc.update("INSERT INTO mall_goods_spec (id, goods_id, name, points, stock, sort, create_time, update_time, "
                + "is_deleted) VALUES (?, ?, '迷你', 8, ?, 1, NOW(), NOW(), 0)", CHEAP_SPEC_ID, GOODS_ID, STOCK);
        phone = String.format("137%08d", PHONE_SEQ.incrementAndGet() % 100_000_000L);
        volunteer = insertVolunteer(phone);
        givePoints(volunteer, 100);
        sms.clear();
    }

    @Test
    void exchangeCouponMakesOrderFreeAndWritesNoZeroPointFlow() {
        Long grantId = grantTo(volunteer, exchangeCoupon());

        MallOrder order = orderService.placeOrder(volunteer, SPEC_ID, grantId);

        assertEquals(0, order.getPoints(), "兑换卷全额抵扣，实付 0");
        assertEquals(PRICE, order.getOriginalPoints(), "标价快照");
        assertEquals(PRICE, order.getCouponDeductPoints());
        assertTrue(order.getCouponName().startsWith("用例卷-"), "卷名快照");
        assertEquals(100, pointService.balanceOf(volunteer), "0 积分的单不动余额");
        assertEquals(0, exchangeFlowsOf(order.getId()), "账本不收 0 分流水——这张单确实没花积分");
        assertEquals(MallCouponGrantStatus.USED, grantStatus(grantId));
        assertEquals(order.getId(), usedOrderOf(grantId), "卷要记住用在哪张单上，退单才还得回去");
        assertEquals(STOCK - 1, stock(SPEC_ID), "用卷不影响库存照扣");
    }

    @Test
    void discountCouponPaysTheDifference() {
        Long grantId = grantTo(volunteer, discountCoupon(20, 12));

        MallOrder order = orderService.placeOrder(volunteer, SPEC_ID, grantId);

        assertEquals(PRICE - 12, order.getPoints());
        assertEquals(12, order.getCouponDeductPoints());
        assertEquals(100 - (PRICE - 12), pointService.balanceOf(volunteer), "只扣差额");
        assertEquals(-(PRICE - 12), jdbc.queryForObject(
                "SELECT change_amount FROM point_record WHERE source_type = ? AND source_id = ?",
                Integer.class, PointSourceType.EXCHANGE, order.getId()), "流水金额 = 实付，与订单 points 对得上");
    }

    @Test
    void thresholdNotMetIsRejectedAndNothingMoves() {
        Long grantId = grantTo(volunteer, discountCoupon(20, 5));

        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.placeOrder(volunteer, CHEAP_SPEC_ID, grantId));

        assertTrue(e.getMessage().contains("未达到满减门槛"), e.getMessage());
        assertEquals(STOCK, stock(CHEAP_SPEC_ID), "被拒的单连库存一起回滚");
        assertEquals(MallCouponGrantStatus.UNUSED, grantStatus(grantId));
        assertEquals(100, pointService.balanceOf(volunteer));
    }

    @Test
    void goodsRequiringACouponRejectsNoCouponAndWrongCoupon() {
        Long required = discountCoupon(20, 5);
        Long other = discountCoupon(20, 5);
        Long requiredGrant = grantTo(volunteer, required);
        Long otherGrant = grantTo(volunteer, other);
        jdbc.update("UPDATE mall_goods SET require_coupon_id = ? WHERE id = ?", required, GOODS_ID);

        BusinessException none = assertThrows(BusinessException.class,
                () -> orderService.placeOrder(volunteer, SPEC_ID, null));
        assertTrue(none.getMessage().contains("只能使用"), none.getMessage());
        BusinessException wrong = assertThrows(BusinessException.class,
                () -> orderService.placeOrder(volunteer, SPEC_ID, otherGrant));
        assertTrue(wrong.getMessage().contains("只能使用指定的卷"), wrong.getMessage());
        assertEquals(STOCK, stock(SPEC_ID), "两次被拒都不得扣库存");

        MallOrder ok = orderService.placeOrder(volunteer, SPEC_ID, requiredGrant);
        assertEquals(PRICE - 5, ok.getPoints());
    }

    @Test
    void someoneElsesCouponLooksLikeItDoesNotExist() {
        Long other = insertVolunteer(String.format("136%08d", PHONE_SEQ.incrementAndGet() % 100_000_000L));
        Long grantId = grantTo(other, discountCoupon(20, 5));

        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.placeOrder(volunteer, SPEC_ID, grantId));
        assertEquals("卷不存在", e.getMessage(), "不是本人的与不存在同一句话，防按 id 枚举别人的卷");
    }

    @Test
    void expiredAndNotYetValidCouponsSayWhy() {
        Long expired = grantTo(volunteer, discountCoupon(20, 5));
        jdbc.update("UPDATE mall_coupon_grant SET valid_start = ?, expire_time = ? WHERE id = ?",
                LocalDateTime.now().minusDays(3), LocalDateTime.now().minusSeconds(1), expired);
        assertTrue(assertThrows(BusinessException.class,
                () -> orderService.placeOrder(volunteer, SPEC_ID, expired)).getMessage().contains("已过期"));

        Long future = grantTo(volunteer, discountCoupon(20, 5));
        jdbc.update("UPDATE mall_coupon_grant SET valid_start = ? WHERE id = ?", LocalDateTime.now().plusDays(2), future);
        assertTrue(assertThrows(BusinessException.class,
                () -> orderService.placeOrder(volunteer, SPEC_ID, future)).getMessage().contains("尚未生效"));
    }

    @Test
    void cancelReturnsCouponStockAndPointsTogether() {
        Long grantId = grantTo(volunteer, discountCoupon(20, 12));
        MallOrder order = orderService.placeOrder(volunteer, SPEC_ID, grantId);

        orderService.cancel(order.getId(), volunteer);

        assertEquals(MallOrderStatus.CANCELLED, orderStatus(order.getId()));
        assertEquals(STOCK, stock(SPEC_ID));
        assertEquals(100, pointService.balanceOf(volunteer), "按实付退，不按标价退");
        assertEquals(MallCouponGrantStatus.UNUSED, grantStatus(grantId), "卷要还回去");
        assertNull(usedOrderOf(grantId));
        assertTrue(sms.all().isEmpty(), "自己取消的不发短信");

        MallOrder again = orderService.placeOrder(volunteer, SPEC_ID, grantId);
        assertEquals(PRICE - 12, again.getPoints(), "还回来的卷能再用");
    }

    @Test
    void cancellingAFreeOrderReturnsTheCouponAndWritesNoRefundFlow() {
        Long grantId = grantTo(volunteer, exchangeCoupon());
        MallOrder order = orderService.placeOrder(volunteer, SPEC_ID, grantId);

        orderService.cancel(order.getId(), volunteer);

        assertEquals(MallCouponGrantStatus.UNUSED, grantStatus(grantId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM point_record WHERE request_id = ?",
                Integer.class, PointSourceType.MALL_REFUND_REQUEST_PREFIX + order.getId()),
                "没扣过分就不退分——否则那是一笔凭空多出来的积分");
        assertEquals(100, pointService.balanceOf(volunteer));
    }

    @Test
    void rejectReturnsEverythingAndTellsHimByText() {
        Long grantId = grantTo(volunteer, discountCoupon(20, 12));
        MallOrder order = orderService.placeOrder(volunteer, SPEC_ID, grantId);

        orderService.reject(order.getId(), "库存盘点有误", ADMIN);

        assertEquals(MallCouponGrantStatus.UNUSED, grantStatus(grantId));
        assertEquals(100, pointService.balanceOf(volunteer));
        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-MALL-REVIEW");
        assertEquals(1, sent.size(), "驳回要发一条短信");
        assertEquals(phone, sent.get(0).phone());
        assertEquals("保温杯（500ml）", sent.get(0).params().get("goodsName"));
        assertEquals("未通过", sent.get(0).params().get("status"));
        assertTrue(sent.get(0).params().get("remark").contains("库存盘点有误"));
        assertTrue(sent.get(0).params().get("remark").contains("积分与卷已退回"),
                "用了卷又扣了分的单，两样都要说——实际=" + sent.get(0).params().get("remark"));
    }

    @Test
    void approveTellsHimWhereToCollect() {
        MallOrder order = orderService.placeOrder(volunteer, SPEC_ID, null);

        orderService.approve(order.getId(), ADMIN);

        List<RecordingSmsService.Sent> sent = sms.byTemplateId("T-MALL-REVIEW");
        assertEquals(1, sent.size());
        assertEquals("通过", sent.get(0).params().get("status"));
        assertEquals("保温杯（500ml）", sent.get(0).params().get("goodsName"));
        assertTrue(sent.get(0).params().get("remark").contains("取货码"), sent.get(0).params().get("remark"));
    }

    @Test
    void failedApproveSendsNothing() {
        MallOrder order = orderService.placeOrder(volunteer, SPEC_ID, null);
        orderService.cancel(order.getId(), volunteer);

        assertThrows(BusinessException.class, () -> orderService.approve(order.getId(), ADMIN));
        assertTrue(sms.all().isEmpty(), "没审核成的单不得发「审核通过」——短信撤不回");
    }

    /**
     * 归还的 CAS 条件里有 {@code used_order_id = 本单}：卷被 A 单用过、A 退回、又被 B 单用上之后，
     * 再对 A 做一次归还（重放）不得把 B 正在用的卷抢回来。
     */
    @Test
    void restoringForAnOldOrderDoesNotStealTheCouponFromTheCurrentOne() {
        Long grantId = grantTo(volunteer, discountCoupon(20, 5));
        MallOrder a = orderService.placeOrder(volunteer, SPEC_ID, grantId);
        orderService.cancel(a.getId(), volunteer);
        MallOrder b = orderService.placeOrder(volunteer, SPEC_ID, grantId);

        couponService.restore(grantId, a.getId());

        assertEquals(MallCouponGrantStatus.USED, grantStatus(grantId));
        assertEquals(b.getId(), usedOrderOf(grantId), "B 单正在用这张卷，A 的迟到归还不得动它");
    }

    @Test
    void aUsedCouponCannotBeUsedAgain() {
        Long grantId = grantTo(volunteer, discountCoupon(20, 5));
        orderService.placeOrder(volunteer, SPEC_ID, grantId);

        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.placeOrder(volunteer, SPEC_ID, grantId));
        assertTrue(e.getMessage().contains("已被使用"), e.getMessage());
        assertEquals(STOCK - 1, stock(SPEC_ID), "第二单整体回滚");
    }

    // ---------------- helpers ----------------

    private Long exchangeCoupon() {
        MallCouponSaveDTO d = baseDto(MallCouponType.EXCHANGE);
        d.setGoodsId(GOODS_ID);
        return couponService.create(d, ADMIN);
    }

    private Long discountCoupon(int threshold, int discount) {
        MallCouponSaveDTO d = baseDto(MallCouponType.DISCOUNT);
        d.setThresholdPoints(threshold);
        d.setDiscountPoints(discount);
        return couponService.create(d, ADMIN);
    }

    private static MallCouponSaveDTO baseDto(int type) {
        MallCouponSaveDTO d = new MallCouponSaveDTO();
        d.setName("用例卷-" + System.nanoTime());
        d.setType(type);
        d.setValidStart(LocalDateTime.now().minusDays(1));
        d.setValidEnd(LocalDateTime.now().plusDays(30));
        return d;
    }

    private Long grantTo(Long volunteerId, Long couponId) {
        MallCouponGrantDTO g = new MallCouponGrantDTO();
        g.setRequestId("rid-order-" + System.nanoTime());
        g.setVolunteerIds(List.of(volunteerId));
        couponService.grant(couponId, g, ADMIN);
        return jdbc.queryForObject("SELECT id FROM mall_coupon_grant WHERE coupon_id = ? AND volunteer_id = ?",
                Long.class, couponId, volunteerId);
    }

    private Long insertVolunteer(String phonePlain) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_coupon_order_" + System.nanoTime());
        v.setRealName("兑换人");
        v.setPhone(cryptoUtil.encrypt(phonePlain));
        v.setPhoneHash(cryptoUtil.hashPhone(phonePlain));
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private void givePoints(Long volunteerId, int amount) {
        jdbc.update("INSERT INTO point_record (volunteer_id, change_amount, source_type, source_id, remark, "
                + "operator_type, create_time, update_time, is_deleted) VALUES (?, ?, ?, NULL, '用例预置', 0, NOW(), NOW(), 0)",
                volunteerId, amount, PointSourceType.MANUAL);
    }

    private int stock(long specId) {
        return jdbc.queryForObject("SELECT stock FROM mall_goods_spec WHERE id = ?", Integer.class, specId);
    }

    private int grantStatus(Long grantId) {
        return jdbc.queryForObject("SELECT status FROM mall_coupon_grant WHERE id = ?", Integer.class, grantId);
    }

    private Long usedOrderOf(Long grantId) {
        return jdbc.queryForObject("SELECT used_order_id FROM mall_coupon_grant WHERE id = ?", Long.class, grantId);
    }

    private int orderStatus(Long orderId) {
        return jdbc.queryForObject("SELECT status FROM mall_order WHERE id = ?", Integer.class, orderId);
    }

    private int exchangeFlowsOf(Long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM point_record WHERE source_type = ? AND source_id = ?",
                Integer.class, PointSourceType.EXCHANGE, orderId);
    }
}
