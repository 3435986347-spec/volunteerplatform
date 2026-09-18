package com.hengde.donate;

import com.hengde.activity.constant.PointSourceType;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonationFlow;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.constant.MallOrderStatus;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.service.CrowdfundService;
import com.hengde.donate.service.DonationService;
import com.hengde.donate.service.MallOrderService;
import com.hengde.donate.vo.DonationVOs;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.service.TradeOrderService;
import com.hengde.trade.vo.TradeVOs;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.next;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 付款成功回写<b>一个线程只用一条连接</b>——连接池只给 1 条，付款回调照样当场把业务单推进（捐款批压测撞出来的）。
 *
 * <p>早先 trade 在事务里发 {@code TradePaidEvent}，订阅方 {@code AFTER_COMMIT} + {@code REQUIRES_NEW} 回写：
 * AFTER_COMMIT 回调跑的时候 trade 事务的连接<b>还没还</b>，{@code REQUIRES_NEW} 再要一条——
 * 每个回调同时占两条。压测里 16 个并发回调把 10 条的池子卡死 30 秒，全部回写失败、连带别的请求拿不到连接。</p>
 *
 * <p>为什么不靠并发去撞：那要求「每个线程都已持有第一条」恰好同时发生，是概率事件。
 * 池子只给 1 条就把它变成确定性的——只要回写还要第二条连接，它就一定拿不到（2 秒超时）、监听器吞掉异常记日志，
 * 业务单停在待支付，下面的断言必红。补偿任务这里不跑，所以「推进了」只可能是事件那条路做到的。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=2000"})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, MallPaymentFakes.class})
class PaymentListenerConnectionTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private TradeOrderService tradeOrderService;
    @Autowired
    private DonationService donationService;
    @Autowired
    private CrowdfundService crowdfundService;
    @Autowired
    private MallOrderService mallOrderService;
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
        assertEquals(1, ((HikariDataSource) dataSource).getMaximumPoolSize(),
                "用例有效性自检：连接池没被限成 1 条，这条用例什么也证明不了");
    }

    @Test
    void crowdfundDonation_isMarkedPaidByTheEvent_withASingleConnection() {
        Long donor = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "单连接捐款人", phone(), true);
        PairDTOs.CrowdfundSave save = new PairDTOs.CrowdfundSave();
        save.setTitle("单连接众筹-" + next());
        save.setTargetAmount(new BigDecimal("1000"));
        Long cf = crowdfundService.create(save, ADMIN);
        crowdfundService.publish(cf);

        DonationVOs.Created created = donationService.donateToCrowdfund(cf, donor,
                DonationServiceTest.crowdfund("12.34", null, false));
        paid(created.getPrepay());

        assertEquals(DonationFlow.PAID, jdbc.queryForObject("SELECT status FROM donate_donation WHERE id = ?",
                Integer.class, created.getDonation().getId()), "付款事件当场把捐款推到已到账");
        assertEquals(0, new BigDecimal("12.34").compareTo(jdbc.queryForObject(
                "SELECT raised_amount FROM donate_crowdfund WHERE id = ?", BigDecimal.class, cf)));
    }

    @Test
    void mallCashOrder_isMovedToPendingReviewByTheEvent_withASingleConnection() {
        long volunteer = 995_000_000L + System.nanoTime() % 1_000_000L;
        long goodsId = next();
        long specId = next();
        jdbc.update("INSERT INTO mall_goods (id, name, status, hidden, sort, create_time, update_time, is_deleted) "
                + "VALUES (?, '单连接保温杯', ?, 0, 0, NOW(), NOW(), 0)", goodsId, MallGoodsStatus.ON_SALE);
        jdbc.update("INSERT INTO mall_goods_spec (id, goods_id, name, points, cash_fen, stock, sort, create_time, "
                + "update_time, is_deleted) VALUES (?, ?, '默认', 30, 800, 5, 0, NOW(), NOW(), 0)", specId, goodsId);
        jdbc.update("INSERT INTO point_record (volunteer_id, change_amount, source_type, source_id, remark, "
                + "operator_type, create_time, update_time, is_deleted) VALUES (?, 100, ?, NULL, '用例预置', 0, NOW(), NOW(), 0)",
                volunteer, PointSourceType.MANUAL);

        MallOrder order = mallOrderService.placeOrder(volunteer, MallExpressOrderTest.pickup(specId));
        assertEquals(MallOrderStatus.AWAITING_PAYMENT, order.getStatus());
        paid(mallOrderService.pay(order.getId(), volunteer, "code-single"));

        assertEquals(MallOrderStatus.PENDING, jdbc.queryForObject("SELECT status FROM mall_order WHERE id = ?",
                Integer.class, order.getId()), "付款事件当场把兑换单推到待审核");
    }

    private void paid(TradeVOs.Prepay prepay) {
        assertTrue(tradeOrderService.applyPaidResult(prepay.getOutTradeNo(), "wx-" + next(), prepay.getAmountFen(),
                LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK));
    }
}
