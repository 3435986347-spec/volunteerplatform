package com.hengde.trade;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.trade.config.TradeProperties;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.dto.TradeDTOs;
import com.hengde.trade.job.TradeReconcileJob;
import com.hengde.trade.service.TradeOrderService;
import com.hengde.trade.service.TradeReconcileService;
import com.hengde.trade.support.FakePaymentGateway;
import com.hengde.trade.vo.TradeVOs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对账——<b>支付回写四道里的第四道</b>（V3规划 D2）。
 *
 * <p>三件事逐条钉住：</p>
 * <ol>
 *   <li><b>两侧都核</b>：只核「本地已支付」的单永远看不见「钱付进了已关闭的单」——
 *       而那正是「钱收了、单没发货」真正落脚的地方；</li>
 *   <li><b>每一次都落库</b>，跳过的也落：只写日志的差异没有人看得到；</li>
 *   <li><b>每日任务真的在跑</b>：核的是前一天、记为定时触发、开关关掉就不跑。</li>
 * </ol>
 *
 * <p>各用例的对账窗口会与别的用例的单重叠（共用一个库），所以只断言「自己那张单」在不在差异里，
 * 不断言总数。<b>需本机 Docker</b>（MySQL）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class TradeReconcileTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);
    private static final long ADMIN = 7701L;

    @Autowired
    private TradeOrderService orderService;
    @Autowired
    private TradeReconcileService reconcileService;
    @Autowired
    private TradeReconcileJob reconcileJob;
    @Autowired
    private TradeProperties properties;
    @Autowired
    private FakePaymentGateway gateway;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    @Test
    void manualReconcile_requiresAWindowAndAnOperator() {
        LocalDateTime now = LocalDateTime.now();
        assertThrows(BusinessException.class, () -> reconcileService.reconcileManually(null, now, ADMIN));
        assertThrows(BusinessException.class, () -> reconcileService.reconcileManually(now, now.minusHours(1), ADMIN));
        assertThrows(BusinessException.class, () -> reconcileService.reconcileManually(now.minusHours(1), now, null),
                "trade 拿不到登录态，操作人由调用方传入、硬校验非空");
    }

    @Test
    void channelOff_isRecordedAsSkipped_notAsClean() {
        gateway.setEnabled(false);
        TradeVOs.Reconciliation r = reconcileService.reconcileManually(
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), ADMIN);

        assertTrue(r.isSkipped(), "渠道没开通就要如实说「没对过」");
        assertEquals(0, r.getMatchedCount(), "跳过时不能报出一个看起来干净的「已核对」数");
        assertNotNull(r.getId(), "「今天没有对过」本身也要落库");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT skipped, trigger_type, operator_id FROM trade_reconcile_run WHERE id = ?", r.getId());
        assertEquals(1, ((Number) row.get("skipped")).intValue());
        assertEquals(TradeReconcileService.TRIGGER_MANUAL, ((Number) row.get("trigger_type")).intValue());
        assertEquals(ADMIN, ((Number) row.get("operator_id")).longValue());
    }

    @Test
    void paidLocallyButNotOnTheChannel_isListedAndReadableLater() {
        String out = newOrder(880).getOutTradeNo();
        // 本地记为已支付，而渠道那边并没有收到这笔——对账要抓的第一种情形
        assertTrue(orderService.applyPaidResult(out, "wx-" + SEQ.incrementAndGet(), 880,
                LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK));

        TradeVOs.Reconciliation r = reconcileNow();

        assertFalse(r.isSkipped());
        assertTrue(r.getMismatches().stream().anyMatch(m -> out.equals(m.getOutTradeNo())),
                "本地已支付、渠道没收到，必须逐条列出来：" + r.getMismatches());
        assertEquals(r.getMismatches().size(), r.getMismatchCount());

        // 落库之后还读得回来：列表按「有差异」筛得到它，详情里逐条差异还在
        TradeVOs.Reconciliation detail = reconcileService.runDetail(r.getId());
        // 假渠道在下单取支付参数时已登记了一笔「未支付」，所以这里的差异是「渠道显示未支付」
        assertTrue(detail.getMismatches().stream().anyMatch(m -> out.equals(m.getOutTradeNo())
                        && m.getNote().contains("渠道显示未支付") && Integer.valueOf(880).equals(m.getLocalAmountFen())),
                "详情必须能把差异原样读回来，否则落库等于没落：" + detail.getMismatches());
        PageQuery q = new PageQuery();
        q.setPage(1);
        q.setSize(100);
        assertTrue(reconcileService.listRuns(q, true, null).getRecords().stream()
                        .anyMatch(x -> r.getId().equals(x.getId())),
                "「只看有差异的」要筛得到这一条");
    }

    @Test
    void closedOrderThatStillReceivedAPayment_isListed() {
        TradeVOs.Prepay order = newOrder(660);
        String out = order.getOutTradeNo();
        orderService.close(order.getTradeOrderId(), ADMIN);
        // 关单之后回调才到：钱是真收了，applyPaidResult「流水照记、状态不改」
        assertFalse(orderService.applyPaidResult(out, "wx-" + SEQ.incrementAndGet(), 660,
                LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK));
        assertEquals(TradeFlow.ORDER_CLOSED, statusOf(out));

        TradeVOs.Reconciliation r = reconcileNow();

        assertTrue(r.getMismatches().stream().anyMatch(m -> out.equals(m.getOutTradeNo())
                        && m.isRemotePaid() && m.getNote().contains("人工退款")),
                "钱付进了已关闭的单——那行 ERROR 日志必须在对账里有一个会被人看到的出口：" + r.getMismatches());
        assertTrue(r.getLocalClosedCount() >= 1);
    }

    @Test
    void closedOrderPaidOnlyOnTheChannelSide_isListed() {
        TradeVOs.Prepay order = newOrder(540);
        String out = order.getOutTradeNo();
        orderService.close(order.getTradeOrderId(), ADMIN);
        // 回调丢了、扫描也不会再捞已关闭的单：只有渠道那边知道钱到了
        gateway.markPaidOnRemote(out, "wx-" + SEQ.incrementAndGet(), 540);

        TradeVOs.Reconciliation r = reconcileNow();

        assertTrue(r.getMismatches().stream().anyMatch(m -> out.equals(m.getOutTradeNo())
                        && m.isRemotePaid() && Integer.valueOf(540).equals(m.getRemoteAmountFen())),
                "本地已关闭、渠道显示已支付，必须列出来：" + r.getMismatches());
    }

    @Test
    void closedAndUnpaidOnBothSides_isConsistent() {
        TradeVOs.Prepay order = newOrder(320);
        String out = order.getOutTradeNo();
        orderService.close(order.getTradeOrderId(), ADMIN);

        TradeVOs.Reconciliation r = reconcileNow();

        assertTrue(r.getMismatches().stream().noneMatch(m -> out.equals(m.getOutTradeNo())),
                "两侧都没付款的已关闭单是一致的，不该被报成差异：" + r.getMismatches());
        assertTrue(r.getMatchedCount() >= 1, "它应当被算进「核对一致」");
    }

    @Test
    void queryFailure_isAMismatch_notASilentPass() {
        String out = newOrder(410).getOutTradeNo();
        assertTrue(orderService.applyPaidResult(out, "wx-" + SEQ.incrementAndGet(), 410,
                LocalDateTime.now().withNano(0), "openid", "{}", TradeFlow.SOURCE_CALLBACK));
        gateway.setQueryFails(true);

        TradeVOs.Reconciliation r = reconcileNow();

        assertTrue(r.getMismatches().stream().anyMatch(m -> out.equals(m.getOutTradeNo())
                        && m.getNote().contains("查单失败")),
                "查不到就是没核对过，不能算进一致：" + r.getMismatches());
    }

    @Test
    void dailyJob_reconcilesYesterday_andCanBeSwitchedOff() {
        LocalDateTime yesterday = LocalDate.now().minusDays(1).atStartOfDay();
        int before = dailyRunsFor(yesterday);

        reconcileJob.reconcileYesterday();
        assertEquals(before + 1, dailyRunsFor(yesterday),
                "每日任务应落一条「定时触发、窗口为前一天」的记录");
        LocalDateTime windowTo = jdbc.queryForObject("SELECT window_to FROM trade_reconcile_run "
                + "WHERE trigger_type = 1 AND window_from = ? ORDER BY id DESC LIMIT 1", LocalDateTime.class, yesterday);
        assertEquals(LocalDate.now().atStartOfDay(), windowTo, "窗口左闭右开，止于今天零点");
        Long operator = jdbc.queryForObject("SELECT operator_id FROM trade_reconcile_run "
                + "WHERE trigger_type = 1 AND window_from = ? ORDER BY id DESC LIMIT 1", Long.class, yesterday);
        assertEquals(null, operator, "定时触发没有操作人");

        boolean original = properties.getReconcile().isEnabled();
        try {
            properties.getReconcile().setEnabled(false);
            reconcileJob.reconcileYesterday();
            assertEquals(before + 1, dailyRunsFor(yesterday), "开关关掉就不该再落记录");
        } finally {
            properties.getReconcile().setEnabled(original);
        }
    }

    @Test
    void reconcileDay_coversThatWholeDay() {
        TradeVOs.Prepay order = newOrder(250);
        String out = order.getOutTradeNo();
        orderService.close(order.getTradeOrderId(), ADMIN);
        gateway.markPaidOnRemote(out, "wx-" + SEQ.incrementAndGet(), 250);

        TradeVOs.Reconciliation today = reconcileService.reconcileDay(LocalDate.now());
        assertEquals(TradeReconcileService.TRIGGER_DAILY, today.getTriggerType());
        assertTrue(today.getMismatches().stream().anyMatch(m -> out.equals(m.getOutTradeNo())),
                "今天关的单应落在「今天」这一段里");

        TradeVOs.Reconciliation tomorrow = reconcileService.reconcileDay(LocalDate.now().plusDays(1));
        assertTrue(tomorrow.getMismatches().stream().noneMatch(m -> out.equals(m.getOutTradeNo())),
                "区间左闭右开：今天的单不该跑进明天那一段");
    }

    // ---------- helpers ----------

    private TradeVOs.Reconciliation reconcileNow() {
        return reconcileService.reconcileManually(
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), ADMIN);
    }

    private TradeVOs.Prepay newOrder(int amountFen) {
        TradeDTOs.CreateOrder d = new TradeDTOs.CreateOrder();
        d.setBizType(TradeFlow.BIZ_CROWDFUND);
        d.setBizNo("rc-biz-" + SEQ.incrementAndGet());
        d.setVolunteerId(3103L);
        d.setPayerOpenid("openid-rc");
        d.setSubject("对账用例捐款");
        d.setAmountFen(amountFen);
        return orderService.createOrder(d);
    }

    private int statusOf(String outTradeNo) {
        return jdbc.queryForObject("SELECT status FROM trade_order WHERE out_trade_no = ?",
                Integer.class, outTradeNo);
    }

    private int dailyRunsFor(LocalDateTime windowFrom) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM trade_reconcile_run WHERE trigger_type = 1 AND window_from = ?",
                Integer.class, windowFrom);
    }
}
