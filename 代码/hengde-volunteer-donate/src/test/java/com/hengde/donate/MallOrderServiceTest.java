package com.hengde.donate;

import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.service.PointService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.constant.MallOrderStatus;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.service.MallOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兑换单：下单扣分 / 退分还库存 / 快照三项。
 *
 * <p>并发与锁序由 {@link MallStockLockOrderTest} 保证，本类管服务层的控制流与账目。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MallOrderServiceTest {

    private static final long GOODS_ID = 992_001L;
    private static final long SPEC_ID = 992_101L;
    private static final long VOLUNTEER_ID = 992_900L;
    private static final int PRICE = 30;

    @Autowired
    private MallOrderService orderService;
    @Autowired
    private PointService pointService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM mall_order WHERE volunteer_id = ?", VOLUNTEER_ID);
        jdbcTemplate.update("DELETE FROM point_record WHERE volunteer_id = ?", VOLUNTEER_ID);
        jdbcTemplate.update("DELETE FROM mall_goods_spec WHERE id = ?", SPEC_ID);
        jdbcTemplate.update("DELETE FROM mall_goods WHERE id = ?", GOODS_ID);
        jdbcTemplate.update("INSERT INTO mall_goods (id, name, status, hidden, sort, create_time, update_time, "
                + "is_deleted) VALUES (?, '保温杯', ?, 0, 0, NOW(), NOW(), 0)", GOODS_ID, MallGoodsStatus.ON_SALE);
        jdbcTemplate.update("INSERT INTO mall_goods_spec (id, goods_id, name, points, stock, sort, "
                + "create_time, update_time, is_deleted) VALUES (?, ?, '500ml', ?, 3, 0, NOW(), NOW(), 0)",
                SPEC_ID, GOODS_ID, PRICE);
        givePoints(100);
    }

    @Test
    void placeOrderDeductsPointsAndStockAndSnapshotsThreeFields() {
        MallOrder order = orderService.placeOrder(VOLUNTEER_ID, SPEC_ID);

        assertNotNull(order.getId());
        assertEquals(MallOrderStatus.PENDING, order.getStatus(), "下单落待审核");
        assertEquals(100 - PRICE, pointService.balanceOf(VOLUNTEER_ID), "下单即扣分");
        assertEquals(2, stock(), "库存扣 1");
        // D8：三项快照
        assertEquals("保温杯", order.getGoodsName());
        assertEquals("500ml", order.getSpecName());
        assertEquals(PRICE, order.getPoints());
        assertTrue(order.getOrderNo().startsWith("DH"), "单号形如 DH+时间戳+随机");
    }

    /**
     * <b>D8 的意义在这条用例里才显出来</b>：下单之后规格改价、改名、甚至被删，
     * 订单里仍是「当时买的是什么、花了多少分」。没有快照的话历史订单会变成空壳。
     */
    @Test
    void snapshotSurvivesLaterSpecChanges() {
        MallOrder order = orderService.placeOrder(VOLUNTEER_ID, SPEC_ID);

        jdbcTemplate.update("UPDATE mall_goods_spec SET name = '改名了', points = 999 WHERE id = ?", SPEC_ID);
        jdbcTemplate.update("UPDATE mall_goods_spec SET is_deleted = 1 WHERE id = ?", SPEC_ID);
        jdbcTemplate.update("UPDATE mall_goods SET name = '商品也改名了' WHERE id = ?", GOODS_ID);

        MallOrder reloaded = jdbcTemplate.queryForObject(
                "SELECT goods_name, spec_name, points FROM mall_order WHERE id = ?",
                (rs, i) -> {
                    MallOrder o = new MallOrder();
                    o.setGoodsName(rs.getString(1));
                    o.setSpecName(rs.getString(2));
                    o.setPoints(rs.getInt(3));
                    return o;
                }, order.getId());
        assertEquals("保温杯", reloaded.getGoodsName());
        assertEquals("500ml", reloaded.getSpecName());
        assertEquals(PRICE, reloaded.getPoints());
    }

    @Test
    void cancelRefundsPointsAndRestoresStock() {
        MallOrder order = orderService.placeOrder(VOLUNTEER_ID, SPEC_ID);
        orderService.cancel(order.getId(), VOLUNTEER_ID);

        assertEquals(100, pointService.balanceOf(VOLUNTEER_ID), "退分后余额回到原值");
        assertEquals(3, stock(), "库存还回去");
        assertEquals(MallOrderStatus.CANCELLED, statusOf(order.getId()));
    }

    /**
     * <b>D6</b>：双击取消只退一次。幂等靠订单状态的 CAS，不靠退分那一步自己判重。
     */
    @Test
    void cancelIsIdempotent() {
        MallOrder order = orderService.placeOrder(VOLUNTEER_ID, SPEC_ID);
        orderService.cancel(order.getId(), VOLUNTEER_ID);

        assertThrows(BusinessException.class, () -> orderService.cancel(order.getId(), VOLUNTEER_ID),
                "已取消的单不得再取消");
        assertEquals(100, pointService.balanceOf(VOLUNTEER_ID), "不得退第二次");
        assertEquals(3, stock(), "库存不得还第二次");
    }

    /**
     * <b>D4</b>：退分那笔用的是 EXCHANGE 来源码 + {@code sys:mall-refund:} 幂等键。
     *
     * <p>用 EXCHANGE 记正数，净额自动归零——换一个非消费类来源码会让
     * 「已使用积分」与「累计获得」同时算错。</p>
     */
    @Test
    void refundUsesExchangeSourceAndReservedRequestId() {
        MallOrder order = orderService.placeOrder(VOLUNTEER_ID, SPEC_ID);
        orderService.cancel(order.getId(), VOLUNTEER_ID);

        Integer refundRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM point_record WHERE volunteer_id = ? AND source_type = ? "
                        + "AND change_amount = ? AND request_id = ?", Integer.class,
                VOLUNTEER_ID, PointSourceType.EXCHANGE, PRICE,
                PointSourceType.MALL_REFUND_REQUEST_PREFIX + order.getId());
        assertEquals(1, refundRows, "退分应是一笔 EXCHANGE 正数，幂等键带系统保留前缀");

        Integer earned = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(change_amount), 0) FROM point_record WHERE volunteer_id = ? "
                        + "AND source_type = ?", Integer.class, VOLUNTEER_ID, PointSourceType.EXCHANGE);
        assertEquals(0, earned, "买了又退，兑换来源的净额必须归零");
    }

    /** 余额不足时整笔回滚：既不建单，也不扣库存。 */
    @Test
    void insufficientBalanceRollsBackStock() {
        jdbcTemplate.update("DELETE FROM point_record WHERE volunteer_id = ?", VOLUNTEER_ID);
        givePoints(PRICE - 1);

        assertThrows(BusinessException.class, () -> orderService.placeOrder(VOLUNTEER_ID, SPEC_ID));
        assertEquals(3, stock(), "下单失败不得吃掉库存");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mall_order WHERE volunteer_id = ?", Integer.class, VOLUNTEER_ID));
    }

    /** 商品下架后不可下单，且文案要说得准（不能把「已下架」报成「库存不足」）。 */
    @Test
    void unlistedGoodsRejectedWithAccurateMessage() {
        jdbcTemplate.update("UPDATE mall_goods SET status = ? WHERE id = ?", MallGoodsStatus.DISABLED, GOODS_ID);
        BusinessException e = assertThrows(BusinessException.class,
                () -> orderService.placeOrder(VOLUNTEER_ID, SPEC_ID));
        assertEquals("该商品已下架", e.getMessage());
    }

    // ---------- helpers ----------

    private void givePoints(int amount) {
        jdbcTemplate.update("INSERT INTO point_record (volunteer_id, change_amount, source_type, source_id, "
                + "remark, operator_type, create_time, update_time, is_deleted) "
                + "VALUES (?, ?, ?, NULL, '用例预置', 0, NOW(), NOW(), 0)",
                VOLUNTEER_ID, amount, PointSourceType.MANUAL);
    }

    private int stock() {
        return jdbcTemplate.queryForObject(
                "SELECT stock FROM mall_goods_spec WHERE id = ?", Integer.class, SPEC_ID);
    }

    private int statusOf(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM mall_order WHERE id = ?", Integer.class, orderId);
    }
}
