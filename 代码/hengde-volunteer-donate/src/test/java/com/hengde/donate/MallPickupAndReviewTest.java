package com.hengde.donate;

import com.hengde.activity.constant.PointSourceType;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.pickup.PickupCodeUtil;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.constant.MallOrderStatus;
import com.hengde.donate.dto.MallReviewDTO;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.service.MallOrderService;
import com.hengde.donate.service.MallReviewService;
import com.hengde.donate.vo.ExchangeRecordVO;
import com.hengde.donate.vo.MallOrderVO;
import com.hengde.donate.vo.MallReviewVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核通过 → 取货码 → 现场核销 → 评价，这条闭环上的四道闸门。
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MallPickupAndReviewTest {

    private static final long GOODS_ID = 994_001L;
    private static final long SPEC_ID = 994_101L;
    private static final long VOLUNTEER_ID = 994_900L;
    private static final long OTHER_VOLUNTEER_ID = 994_901L;
    private static final long ADMIN_ID = 994_800L;
    private static final int PRICE = 20;

    @Autowired
    private MallOrderService orderService;
    @Autowired
    private MallReviewService reviewService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM mall_goods_review WHERE volunteer_id IN (?, ?)",
                VOLUNTEER_ID, OTHER_VOLUNTEER_ID);
        jdbcTemplate.update("DELETE FROM mall_order WHERE volunteer_id IN (?, ?)",
                VOLUNTEER_ID, OTHER_VOLUNTEER_ID);
        jdbcTemplate.update("DELETE FROM point_record WHERE volunteer_id IN (?, ?)",
                VOLUNTEER_ID, OTHER_VOLUNTEER_ID);
        jdbcTemplate.update("DELETE FROM mall_goods_spec WHERE id = ?", SPEC_ID);
        jdbcTemplate.update("DELETE FROM mall_goods WHERE id = ?", GOODS_ID);
        jdbcTemplate.update("INSERT INTO mall_goods (id, name, status, hidden, sort, create_time, "
                + "update_time, is_deleted) VALUES (?, '帆布袋', ?, 0, 0, NOW(), NOW(), 0)",
                GOODS_ID, MallGoodsStatus.ON_SALE);
        jdbcTemplate.update("INSERT INTO mall_goods_spec (id, goods_id, name, points, stock, sort, "
                + "create_time, update_time, is_deleted) VALUES (?, ?, '米白', ?, 5, 0, NOW(), NOW(), 0)",
                SPEC_ID, GOODS_ID, PRICE);
        givePoints(VOLUNTEER_ID, 100);
        givePoints(OTHER_VOLUNTEER_ID, 100);
    }

    // ---------------- 审核通过与取货码 ----------------

    /**
     * <b>取货码与状态迁移必须同一条语句。</b>
     *
     * <p>分两步的话，中间崩一次就留下「待领取但没有取货码」的单：志愿者点开是空白，
     * 柜台无从核销，而系统里看它一切正常。</p>
     */
    @Test
    void approveIssuesPickupCodeAndSnapshotsSite() {
        MallOrder order = orderService.placeOrder(VOLUNTEER_ID, SPEC_ID);
        orderService.approve(order.getId(), ADMIN_ID);

        MallOrderVO vo = orderService.detailMine(order.getId(), VOLUNTEER_ID);
        assertEquals(MallOrderStatus.READY, vo.getStatus());
        assertNotNull(vo.getPickupCode(), "过审的那一刻就该有取货码");
        assertTrue(PickupCodeUtil.isValid(vo.getPickupCode(), PickupCodeUtil.DOMAIN_MALL),
                "取货码必须是商城域的合法形态");
        assertNotNull(vo.getPickupBarcode(), "详情要给条码图供柜台扫描");
        assertTrue(vo.getPickupBarcode().startsWith("data:image/png;base64,"));
    }

    @Test
    void approveIsCasAndNeedsOperator() {
        MallOrder order = orderService.placeOrder(VOLUNTEER_ID, SPEC_ID);

        assertThrows(BusinessException.class, () -> orderService.approve(order.getId(), null));
        orderService.approve(order.getId(), ADMIN_ID);
        assertThrows(BusinessException.class, () -> orderService.approve(order.getId(), ADMIN_ID),
                "已通过的不得重复审核（重复通过会重新签发取货码，把上一张作废）");
    }

    /** 取货码只在「待领取」时下发；已领取之后再给出去没有用途，只是多一次泄露面。 */
    @Test
    void pickupCodeIsOnlyExposedWhileReady() {
        MallOrder order = orderService.placeOrder(VOLUNTEER_ID, SPEC_ID);
        assertNull(orderService.detailMine(order.getId(), VOLUNTEER_ID).getPickupCode(),
                "待审核时还没有取货码");

        orderService.approve(order.getId(), ADMIN_ID);
        String code = orderService.detailMine(order.getId(), VOLUNTEER_ID).getPickupCode();
        orderService.verify(code, ADMIN_ID);

        MallOrderVO after = orderService.detailMine(order.getId(), VOLUNTEER_ID);
        assertEquals(MallOrderStatus.PICKED, after.getStatus());
        assertNull(after.getPickupCode(), "已领取之后不再下发取货码");
    }

    // ---------------- 核销 ----------------

    @Test
    void verifyIsOneShotAndReportsWhatToHandOver() {
        MallOrder order = orderService.placeOrder(VOLUNTEER_ID, SPEC_ID);
        orderService.approve(order.getId(), ADMIN_ID);
        String code = orderService.detailMine(order.getId(), VOLUNTEER_ID).getPickupCode();

        MallOrderVO verified = orderService.verify(code, ADMIN_ID);
        assertEquals("帆布袋", verified.getGoodsName(), "返回该发什么，供柜台当场核对");
        assertEquals("米白", verified.getSpecName());
        assertEquals(MallOrderStatus.PICKED, verified.getStatus());

        BusinessException again = assertThrows(BusinessException.class,
                () -> orderService.verify(code, ADMIN_ID), "同一个码不得核销两次");
        assertTrue(again.getMessage().contains("已"), "第二次要说清是「已核销过」，不能只报核销失败：" + again.getMessage());
    }

    /** 大小写、空格、连字符都该被规整掉——码是人抄进去的。 */
    @Test
    void verifyAcceptsMessyButEquivalentInput() {
        MallOrder order = orderService.placeOrder(VOLUNTEER_ID, SPEC_ID);
        orderService.approve(order.getId(), ADMIN_ID);
        String code = orderService.detailMine(order.getId(), VOLUNTEER_ID).getPickupCode();

        String messy = " " + code.substring(0, 5).toLowerCase() + "-" + code.substring(5) + "\n";
        assertEquals(MallOrderStatus.PICKED, orderService.verify(messy, ADMIN_ID).getStatus());
    }

    /**
     * 证书域的码在商城柜台核销不掉，<b>哪怕库里真有一张单挂着它</b>。
     *
     * <p>⚠️ 这条用例刻意<b>把码塞进了 mall_order</b>，而不是拿一个库里没有的证书码去试——
     * 后者证明不了任何事：去掉域校验之后，那个码在表里同样查不到，报的还是「取货码无效」，
     * 用例照样绿。<b>只有当那张单确实存在时，「域标记挡住了它」与「查无此单」才区分得开。</b>
     * 去掉 {@code isValid(code, DOMAIN_MALL)} 的第二个参数，本用例必红。</p>
     *
     * <p>库里为什么会有这种行：迁移、人工订正、或将来两个域共用一张核销台时的串号。
     * 域标记的作用正是让这类串号在<b>入口</b>就停住。</p>
     */
    @Test
    void certificateDomainCodeIsRejectedEvenWhenSuchAnOrderExists() {
        MallOrder order = orderService.placeOrder(VOLUNTEER_ID, SPEC_ID);
        orderService.approve(order.getId(), ADMIN_ID);
        String certCode = PickupCodeUtil.generate(PickupCodeUtil.DOMAIN_CERTIFICATE);
        jdbcTemplate.update("UPDATE mall_order SET pickup_code = ? WHERE id = ?", certCode, order.getId());

        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.verify(certCode, ADMIN_ID));
        assertEquals("取货码无效", e.getMessage());
        assertEquals(MallOrderStatus.READY, jdbcTemplate.queryForObject(
                        "SELECT status FROM mall_order WHERE id = ?", Integer.class, order.getId()),
                "那张单不得被核销掉——域标记就是在入口把它挡住的");
    }

    @Test
    void verifyRejectsUnknownCodeAndNonReadyOrder() {
        assertThrows(BusinessException.class,
                () -> orderService.verify(PickupCodeUtil.generate(PickupCodeUtil.DOMAIN_MALL), ADMIN_ID),
                "库里没有的码");

        MallOrder order = orderService.placeOrder(VOLUNTEER_ID, SPEC_ID);
        orderService.approve(order.getId(), ADMIN_ID);
        String code = orderService.detailMine(order.getId(), VOLUNTEER_ID).getPickupCode();
        jdbcTemplate.update("UPDATE mall_order SET status = ? WHERE id = ?",
                MallOrderStatus.CANCELLED, order.getId());

        BusinessException e = assertThrows(BusinessException.class, () -> orderService.verify(code, ADMIN_ID));
        assertTrue(e.getMessage().contains("已取消"), "状态不对时要说清是什么状态：" + e.getMessage());
    }

    @Test
    void verifyRequiresOperator() {
        assertThrows(BusinessException.class, () -> orderService.verify("PUM23456789AB", null));
    }

    /**
     * 「抄错了一位」与「没这单」必须分得开。
     *
     * <p>柜台前的人拿到「取货码无效」时无从判断该让志愿者重抄一遍、还是去查订单出了什么问题。
     * <b>区分在这里是安全的</b>：码长与字母表印在志愿者自己的条码上，本就是公开信息。</p>
     *
     * <p>⚠️ 但<b>只分这一刀</b>：形态合法却不属于商城域的码，仍与「查无此单」共用一句
     * （见 {@link #certificateDomainCodeIsRejectedEvenWhenSuchAnOrderExists}）——
     * 分开报等于告诉持码人「你这码是真的，只是走错了窗口」。</p>
     */
    @Test
    void malformedCodeIsDistinguishedFromUnknownCode() {
        BusinessException malformed = assertThrows(BusinessException.class,
                () -> orderService.verify("PUM0O1IABCDEF", ADMIN_ID), "含 0/1/I/O 的一定是抄错的");
        BusinessException unknown = assertThrows(BusinessException.class,
                () -> orderService.verify(PickupCodeUtil.generate(PickupCodeUtil.DOMAIN_MALL), ADMIN_ID),
                "形态合法但库里没有");

        assertTrue(malformed.getMessage().contains("格式"),
                "抄错了要说是格式问题：" + malformed.getMessage());
        assertFalse(unknown.getMessage().contains("格式"),
                "查无此单不能报成格式问题：" + unknown.getMessage());
        assertNotEquals(malformed.getMessage(), unknown.getMessage(),
                "两种失败合用一句话，柜台就分不清该重抄还是该查单");
    }

    // ---------------- 评价的资格闸门 ----------------

    /**
     * <b>没兑换过就不能评。</b>比照 {@code AttendanceService.submitReview}「须实际签到才能评活动」——
     * 没有这道闸门，评价区就是一块任何登录用户都能写字的公共留言板。
     */
    @Test
    void reviewRequiresOwnPickedOrder() {
        MallOrder order = orderService.placeOrder(VOLUNTEER_ID, SPEC_ID);

        assertThrows(BusinessException.class,
                () -> reviewService.submit(VOLUNTEER_ID, order.getId(), review(5, "还没领就评")),
                "待审核的单不能评");

        orderService.approve(order.getId(), ADMIN_ID);
        assertThrows(BusinessException.class,
                () -> reviewService.submit(VOLUNTEER_ID, order.getId(), review(5, "待领取也不行")),
                "领取之后才能评");

        String code = orderService.detailMine(order.getId(), VOLUNTEER_ID).getPickupCode();
        orderService.verify(code, ADMIN_ID);
        assertNotNull(reviewService.submit(VOLUNTEER_ID, order.getId(), review(5, "很好")));
    }

    /** 别人的单不能拿来评；归属不符与不存在返回同一句话，防按 id 枚举。 */
    @Test
    void cannotReviewSomeoneElsesOrder() {
        Long orderId = pickedOrder(VOLUNTEER_ID);

        BusinessException e = assertThrows(BusinessException.class,
                () -> reviewService.submit(OTHER_VOLUNTEER_ID, orderId, review(1, "蹭评")));
        assertEquals("兑换单不存在", e.getMessage(), "归属不符与不存在必须同一句话");
    }

    /**
     * <b>粒度：一单一评，不是一人一商品一评。</b>
     *
     * <p>唯一键建在 {@code (志愿者, 商品)} 上的话，同一个人兑换两次就评不了第二次——
     * 而他确实又消费了一次。建在订单上则兑换几次就能评几次，且顺带防住重复提交。</p>
     */
    @Test
    void oneReviewPerOrderNotPerVolunteerAndGoods() {
        Long first = pickedOrder(VOLUNTEER_ID);
        reviewService.submit(VOLUNTEER_ID, first, review(5, "第一次买"));

        assertThrows(BusinessException.class,
                () -> reviewService.submit(VOLUNTEER_ID, first, review(4, "同一张单再评一次")),
                "一单一评：重复提交由 uk_order 挡住");

        // 同一个人、同一个商品，第二次兑换 —— 必须还能评
        Long second = pickedOrder(VOLUNTEER_ID);
        assertNotNull(reviewService.submit(VOLUNTEER_ID, second, review(4, "第二次买")),
                "同商品再兑换一次仍应能评——键建在订单上正是为了这个");

        assertEquals(2, reviewService.listMine(VOLUNTEER_ID, new PageQuery()).getRecords().size());
    }

    /** 评的是哪个商品由订单说了算，入参不带 goodsId——否则拿自己的单去评别人的商品。 */
    @Test
    void reviewedGoodsComesFromTheOrder() {
        Long orderId = pickedOrder(VOLUNTEER_ID);
        reviewService.submit(VOLUNTEER_ID, orderId, review(5, "好用"));

        List<MallReviewVO> reviews = reviewService.listByGoods(GOODS_ID, new PageQuery()).getRecords();
        assertEquals(1, reviews.size());
        assertEquals(GOODS_ID, reviews.get(0).getGoodsId());
        assertEquals("帆布袋", reviews.get(0).getGoodsName(), "商品名取订单快照");
    }

    @Test
    void ratingOutOfRangeIsRejected() {
        Long orderId = pickedOrder(VOLUNTEER_ID);
        assertThrows(BusinessException.class, () -> reviewService.submit(VOLUNTEER_ID, orderId, review(0, "零分")));
        assertThrows(BusinessException.class, () -> reviewService.submit(VOLUNTEER_ID, orderId, review(6, "六分")));
    }

    @Test
    void delistedReviewDisappearsFromGoodsList() {
        Long orderId = pickedOrder(VOLUNTEER_ID);
        Long reviewId = reviewService.submit(VOLUNTEER_ID, orderId, review(1, "不当言论"));

        reviewService.delist(reviewId);
        assertTrue(reviewService.listByGoods(GOODS_ID, new PageQuery()).getRecords().isEmpty());
    }

    /** 「我的兑换」要显示评价状态（Row 8 C）。 */
    @Test
    void myOrdersCarryReviewedFlag() {
        Long orderId = pickedOrder(VOLUNTEER_ID);
        assertFalse(orderService.listMine(VOLUNTEER_ID, new PageQuery(), null)
                .getRecords().get(0).getReviewed());

        reviewService.submit(VOLUNTEER_ID, orderId, review(5, "好"));
        assertTrue(orderService.listMine(VOLUNTEER_ID, new PageQuery(), null)
                .getRecords().get(0).getReviewed());
    }

    // ---------------- 公开兑换记录 ----------------

    /**
     * 公开列表<b>不含取货码</b>——它是柜台上的持有者凭据，在人人可见的列表里出现一次，
     * 就等于把东西送给任何看见的人。VO 的字段集就是这道闸门（结构上没有那个字段）。
     */
    @Test
    void publicRecordsExcludeInProgressOrdersAndNeverCarryCodes() {
        MallOrder pending = orderService.placeOrder(VOLUNTEER_ID, SPEC_ID);
        MallOrder ready = orderService.placeOrder(OTHER_VOLUNTEER_ID, SPEC_ID);
        orderService.approve(ready.getId(), ADMIN_ID);

        List<ExchangeRecordVO> records = orderService.listExchangeRecords(new PageQuery()).getRecords();
        List<String> names = records.stream().map(ExchangeRecordVO::getGoodsName).toList();
        assertTrue(names.contains("帆布袋"));
        assertEquals(1, records.stream()
                        .filter(r -> "帆布袋".equals(r.getGoodsName())).count(),
                "待审核的那张不该公开——过程态会让人以为「他兑到了」。pendingId=" + pending.getId());
    }

    // ---------------- helpers ----------------

    private Long pickedOrder(Long volunteerId) {
        MallOrder order = orderService.placeOrder(volunteerId, SPEC_ID);
        orderService.approve(order.getId(), ADMIN_ID);
        String code = orderService.detailMine(order.getId(), volunteerId).getPickupCode();
        orderService.verify(code, ADMIN_ID);
        return order.getId();
    }

    private MallReviewDTO review(int rating, String content) {
        MallReviewDTO dto = new MallReviewDTO();
        dto.setRating(rating);
        dto.setContent(content);
        return dto;
    }

    private void givePoints(Long volunteerId, int amount) {
        jdbcTemplate.update("INSERT INTO point_record (volunteer_id, change_amount, source_type, source_id, "
                        + "remark, operator_type, create_time, update_time, is_deleted) "
                        + "VALUES (?, ?, ?, NULL, '用例预置', 0, NOW(), NOW(), 0)",
                volunteerId, amount, PointSourceType.MANUAL);
    }
}
