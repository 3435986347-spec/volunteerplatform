package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.entity.DonateShipment;
import com.hengde.donate.logistics.Kuaidi100PushClient;
import com.hengde.donate.logistics.LogisticsClient;
import com.hengde.donate.logistics.LogisticsPushClient;
import com.hengde.donate.service.BookCampaignService;
import com.hengde.donate.service.DonateLogisticsPushService;
import com.hengde.donate.service.DonateShipmentService;
import com.hengde.donate.service.DonateTrackService;
import com.hengde.donate.vo.DonateFlowVOs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hengde.donate.BookDonationTestSupport.expressNo;
import static com.hengde.donate.BookDonationTestSupport.item;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static com.hengde.donate.BookDonationTestSupport.shipment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快递100 订阅推送（V3 物流推送批）。
 *
 * <p>订阅与查询两个快递100 客户端都换成可编程的假实现——没有账号与备案域名时，<b>我们自己的逻辑</b>
 * 必须照样被完整测到：</p>
 * <ul>
 *   <li><b>同一张运单只订一次</b>：重复跑、多线程同时跑都一样（订阅是付费项）；</li>
 *   <li><b>失败分流</b>：「重复订阅」算成功、被拒直接放弃、可重试的按间隔重试到上限再放弃，放弃的交还轮询；</li>
 *   <li><b>推送验签</b>：每单自己的 salt、单号必须对得上；</li>
 *   <li><b>乱序推送</b>不把新快照盖回旧的；shutdown / abort 的状态迁移只朝前走；</li>
 *   <li><b>订阅中的运单不再按次查询</b>——轮询跳过、志愿者查看也不触发。</li>
 * </ul>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {
        "hengde.donate.logistics.push.enabled=true",
        "hengde.donate.logistics.push.callback-url=https://example.test/api/callback/logistics/kuaidi100",
        "hengde.donate.logistics.push.retry-interval-minutes=30",
        "hengde.donate.logistics.push.max-attempts=3",
        // 共享容器里别的用例也留下了在途运单：批量给大，本类造的单才不会被挤到下一轮
        "hengde.donate.logistics.push.subscribe-batch=5000",
        "hengde.donate.logistics.poll-interval-minutes=60",
        "hengde.donate.logistics.poll-batch=5000",
        "hengde.donate.logistics.track-max-days=30",
        "hengde.donate.wish.recv-phone=0759-6666666"})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, DonateLogisticsPushTest.Fakes.class})
class DonateLogisticsPushTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 9, 14, 18, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 9, 15, 10, 0);

    @Autowired
    private DonateLogisticsPushService pushService;
    @Autowired
    private DonateTrackService trackService;
    @Autowired
    private DonateShipmentService shipmentService;
    @Autowired
    private BookCampaignService campaignService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FakePush push;
    @Autowired
    private DonateTrackServiceTest.FakeClient query;

    private Long donor;
    private Long campaign;

    @BeforeEach
    void reset() {
        push.reset();
        query.reset();
        campaign = BookDonationTestSupport.openCampaign(campaignService);
        donor = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "等推送的人", phone(), true);
    }

    // ================= 订阅 =================

    @Test
    void eachInTransitShipmentIsSubscribedOnce_evenWhenTheJobRunsAgain() {
        DonateFlowVOs.Shipment a = register();
        DonateFlowVOs.Shipment b = register();

        pushService.subscribeDue();
        pushService.subscribeDue();
        pushService.subscribeDue();

        for (DonateFlowVOs.Shipment s : List.of(a, b)) {
            String no = noOf(s.getId());
            assertEquals(1, push.countFor(no), "订阅是付费项，重复跑任务也只能订一次：" + no);
            Map<String, Object> row = row(s.getId());
            assertEquals(DonateFlow.SUBSCRIBE_ACTIVE, ((Number) row.get("subscribe_status")).intValue());
            assertEquals(1, ((Number) row.get("subscribe_attempts")).intValue());
            assertTrue(String.valueOf(row.get("subscribe_salt")).matches("[0-9a-f]{32}"), "每单一个随机 salt");
            FakePush.Call call = push.lastFor(no);
            assertEquals("https://example.test/api/callback/logistics/kuaidi100?sid=" + s.getId(), call.callbackUrl,
                    "回调地址里带运单 id，验签才知道该用哪一单的 salt");
            assertEquals(row.get("subscribe_salt"), call.salt, "交给快递100 的就是落库的那个 salt");
            assertEquals("0759-8888888", call.phone, "顺丰要收件电话：寄往协会的包裹就是活动预留电话");
        }
    }

    @Test
    void concurrentRunsStillSubscribeEachShipmentOnce() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(register().getId());
        }
        push.delayMs = 40;   // 把「占住之后、应答之前」的窗口拉长，让重叠真的发生

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                start.await();
                return pushService.subscribeDue();
            }));
        }
        start.countDown();
        for (Future<Integer> f : fs) {
            f.get(2, TimeUnit.MINUTES);
        }
        pool.shutdownNow();

        for (Long id : ids) {
            assertEquals(1, push.countFor(noOf(id)), "8 个任务同时跑，同一张运单也只能订一次：" + id);
            assertEquals(DonateFlow.SUBSCRIBE_ACTIVE, ((Number) row(id).get("subscribe_status")).intValue());
        }
    }

    @Test
    void duplicateSubscriptionIsTreatedAsAccepted() {
        DonateFlowVOs.Shipment s = register();
        push.next = new LogisticsPushClient.SubscribeResult(true, false, "501", "POLL:重复订阅");

        pushService.subscribeDue();

        assertEquals(DonateFlow.SUBSCRIBE_ACTIVE, ((Number) row(s.getId()).get("subscribe_status")).intValue(),
                "「重复订阅」说明上次其实订上了，当成失败会无限重试");
    }

    @Test
    void rejectedNumberGivesUpAtOnce_andPollingTakesItBack() {
        DonateFlowVOs.Shipment rejected = register();
        DonateFlowVOs.Shipment subscribed = register();
        String rejectedNo = noOf(rejected.getId());
        push.rejectPermanently.add(rejectedNo);

        pushService.subscribeDue();
        pushService.subscribeDue();

        assertEquals(1, push.countFor(rejectedNo), "被明确拒绝的不重试");
        Map<String, Object> row = row(rejected.getId());
        assertEquals(DonateFlow.SUBSCRIBE_GAVE_UP, ((Number) row.get("subscribe_status")).intValue());
        assertTrue(String.valueOf(row.get("subscribe_error")).startsWith("701"), "放弃要留下原因：" + row);

        query.next = new LogisticsClient.TrackResult(0, List.of(), "{}");
        trackService.pollDue();
        assertTrue(query.nos.contains(rejectedNo), "订不上的交还轮询，轨迹不能因此再也不更新");
        assertFalse(query.nos.contains(noOf(subscribed.getId())), "订上了的由推送保持快照，轮询再查一遍是重复付费");
    }

    @Test
    void transientFailuresRetryAfterTheIntervalThenGiveUp() {
        DonateFlowVOs.Shipment s = register();
        String no = noOf(s.getId());
        push.failTransiently.add(no);

        pushService.subscribeDue();
        assertEquals(1, push.countFor(no));
        assertEquals(DonateFlow.SUBSCRIBE_PENDING, ((Number) row(s.getId()).get("subscribe_status")).intValue(),
                "可重试的失败不放弃");
        assertNotNull(row(s.getId()).get("subscribe_error"));

        pushService.subscribeDue();
        assertEquals(1, push.countFor(no), "没过重试间隔不重试");

        for (int attempt = 2; attempt <= 3; attempt++) {
            backdateAttempt(s.getId());
            pushService.subscribeDue();
            assertEquals(attempt, push.countFor(no));
        }
        assertEquals(DonateFlow.SUBSCRIBE_GAVE_UP, ((Number) row(s.getId()).get("subscribe_status")).intValue(),
                "失败满上限（3 次）就放弃、交还轮询");
        backdateAttempt(s.getId());
        pushService.subscribeDue();
        assertEquals(3, push.countFor(no), "放弃之后不再订");
    }

    @Test
    void resubscribeKeepsTheSameSalt() {
        DonateFlowVOs.Shipment s = register();
        String no = noOf(s.getId());
        push.rejectPermanently.add(no);
        pushService.subscribeDue();
        String salt = (String) row(s.getId()).get("subscribe_salt");
        assertThrows(BusinessException.class, () -> pushService.resubscribe(s.getId(), null), "操作人必填");

        push.rejectPermanently.clear();
        pushService.resubscribe(s.getId(), BookDonationTestSupport.ADMIN);
        pushService.subscribeDue();

        assertEquals(DonateFlow.SUBSCRIBE_ACTIVE, ((Number) row(s.getId()).get("subscribe_status")).intValue());
        assertEquals(salt, push.lastFor(no).salt,
                "salt 首次生成后不再换：上一次其实订上了的话，快递100 手里是旧 salt");
        BusinessException again = assertThrows(BusinessException.class,
                () -> pushService.resubscribe(s.getId(), BookDonationTestSupport.ADMIN));
        assertTrue(again.getMessage().contains("订阅中"), "订阅中的不能重新订阅：" + again.getMessage());
    }

    @Test
    void channelOffSubscribesNothing() {
        DonateFlowVOs.Shipment s = register();
        push.enabled = false;

        assertEquals(0, pushService.subscribeDue());
        assertEquals(0, push.countFor(noOf(s.getId())));
    }

    // ================= 推送回调 =================

    @Test
    void validPushUpdatesTheSnapshot_andSelfHealsALostSubscribeAck() {
        DonateFlowVOs.Shipment s = register();
        String no = noOf(s.getId());
        push.enabled = true;
        push.next = new LogisticsPushClient.SubscribeResult(false, false, "500", "服务繁忙");
        pushService.subscribeDue();   // 快递100 其实订上了、只是我们拿到的是失败——库里仍是「待订阅」
        String salt = (String) row(s.getId()).get("subscribe_salt");

        String param = pushParam("polling", no, 0, T2, "快件已到达湛江转运中心", T1, "快件已揽收");
        DonateLogisticsPushService.Ack ack = pushService.handleCallback(s.getId(), param, md5(param + salt));

        assertTrue(ack.success(), ack.message());
        Map<String, Object> row = row(s.getId());
        assertEquals("快件已到达湛江转运中心", row.get("track_last_context"));
        assertEquals(T2, row.get("track_last_time"));
        assertNotNull(row.get("push_time"));
        assertEquals(DonateFlow.SUBSCRIBE_ACTIVE, ((Number) row.get("subscribe_status")).intValue(),
                "推送能到，说明订阅确实在跑：丢了应答而停在「待订阅」的借这条推送自愈");
        DonateFlowVOs.Track t = trackService.trackForAdmin(s.getId(), false);
        assertEquals(2, t.getNodes().size(), "推送写的是完整轨迹，查看时读得回来");
    }

    @Test
    void badSignatureOrForeignNumberIsRejectedAndChangesNothing() {
        DonateFlowVOs.Shipment s = subscribedShipment();
        String no = noOf(s.getId());
        String salt = (String) row(s.getId()).get("subscribe_salt");
        String param = pushParam("polling", no, 0, T2, "伪造的节点", null, null);

        assertFalse(pushService.handleCallback(s.getId(), param, md5(param + "wrong-salt")).success(),
                "验签失败必须回失败");
        assertFalse(pushService.handleCallback(s.getId(), param, null).success());
        String foreign = pushParam("polling", "SF-NOT-THIS-ONE", 0, T2, "别人的轨迹", null, null);
        assertFalse(pushService.handleCallback(s.getId(), foreign, md5(foreign + salt)).success(),
                "签名对、单号不对：不能把别人的轨迹写到这张运单上");

        assertNull(row(s.getId()).get("track_last_context"), "被拒的推送一个字段都不该写进去");
    }

    @Test
    void anOlderPushArrivingLateDoesNotOverwriteANewerSnapshot() {
        DonateFlowVOs.Shipment s = subscribedShipment();
        String no = noOf(s.getId());
        String salt = (String) row(s.getId()).get("subscribe_salt");
        String newer = pushParam("polling", no, 0, T2, "快件已到达湛江转运中心", T1, "快件已揽收");
        String older = pushParam("polling", no, 1, T1, "快件已揽收", null, null);

        assertTrue(pushService.handleCallback(s.getId(), newer, md5(newer + salt)).success());
        assertTrue(pushService.handleCallback(s.getId(), older, md5(older + salt)).success(),
                "晚到的旧推送也要回成功，否则快递100 会一直重推它");

        Map<String, Object> row = row(s.getId());
        assertEquals(T2, row.get("track_last_time"), "晚到的旧推送不能把快照盖回去");
        assertEquals("快件已到达湛江转运中心", row.get("track_last_context"));
    }

    @Test
    void shutdownEndsTracking_abortHandsBackToPolling_andALateStatusDoesNotReviveIt() {
        DonateFlowVOs.Shipment finished = subscribedShipment();
        DonateFlowVOs.Shipment aborted = subscribedShipment();
        String saltF = (String) row(finished.getId()).get("subscribe_salt");
        String saltA = (String) row(aborted.getId()).get("subscribe_salt");

        String shutdown = pushParam("shutdown", noOf(finished.getId()), 3, T2, "已签收", T1, "快件已揽收");
        assertTrue(pushService.handleCallback(finished.getId(), shutdown, md5(shutdown + saltF)).success());
        Map<String, Object> f = row(finished.getId());
        assertEquals(DonateFlow.SUBSCRIBE_FINISHED, ((Number) f.get("subscribe_status")).intValue());
        assertEquals(1, ((Number) f.get("track_done")).intValue(), "签收即终态");

        String abort = abortParam(noOf(aborted.getId()), "3天查询无记录");
        assertTrue(pushService.handleCallback(aborted.getId(), abort, md5(abort + saltA)).success());
        assertEquals(DonateFlow.SUBSCRIBE_ABORTED, ((Number) row(aborted.getId()).get("subscribe_status")).intValue());

        // 迟到的 polling 推送不能把已结束 / 已中止的拉回「订阅中」
        String lateF = pushParam("polling", noOf(finished.getId()), 0, T1, "快件已揽收", null, null);
        pushService.handleCallback(finished.getId(), lateF, md5(lateF + saltF));
        String lateA = pushParam("polling", noOf(aborted.getId()), 0, T1, "快件已揽收", null, null);
        pushService.handleCallback(aborted.getId(), lateA, md5(lateA + saltA));
        assertEquals(DonateFlow.SUBSCRIBE_FINISHED, ((Number) row(finished.getId()).get("subscribe_status")).intValue());
        assertEquals(DonateFlow.SUBSCRIBE_ABORTED, ((Number) row(aborted.getId()).get("subscribe_status")).intValue());

        // 推送本身也刷新了查询时间（它就是「最近一次拿到数据」）——等过一个轮询间隔，轮询才会接手
        jdbc.update("UPDATE donate_shipment SET track_query_time = ? WHERE id IN (?, ?)",
                LocalDateTime.now().minusHours(2), finished.getId(), aborted.getId());
        query.next = new LogisticsClient.TrackResult(0, List.of(), "{}");
        trackService.pollDue();
        assertTrue(query.nos.contains(noOf(aborted.getId())), "被快递100 中止的交还轮询");
        assertFalse(query.nos.contains(noOf(finished.getId())), "推送结束的已到终态");
    }

    @Test
    void unknownShipmentIsAcknowledgedAndIgnored() {
        assertTrue(pushService.handleCallback(null, "{}", "x").success(), "缺运单 id：没有东西可改，别让快递100 一直重推");
        assertTrue(pushService.handleCallback(Long.MAX_VALUE, "{}", "x").success());
        DonateFlowVOs.Shipment never = register();   // 从没订阅过的运单没有 salt，验不了签也改不了任何东西
        assertTrue(pushService.handleCallback(never.getId(), "{}", "x").success());
        assertNull(row(never.getId()).get("track_last_context"));
    }

    @Test
    void volunteersViewingASubscribedShipmentDoNotTriggerAPaidQuery() {
        DonateFlowVOs.Shipment s = subscribedShipment();
        jdbc.update("UPDATE donate_shipment SET track_query_time = ? WHERE id = ?",
                LocalDateTime.now().minusDays(1), s.getId());

        trackService.trackForDonor(s.getId(), donor);
        assertEquals(0, query.calls.get(), "订阅中的运单由推送保持快照，志愿者每看一次就查一次是订阅与查询各付一份钱");

        trackService.trackForAdmin(s.getId(), true);
        assertEquals(1, query.calls.get(), "后台显式刷新不受限——推送卡住时那是唯一的手动出口");
    }

    // ================= 报文层 =================

    @Test
    void callbackSignIsUpperHexMd5OfParamAndSalt() {
        String param = "{\"status\":\"polling\"}";
        assertEquals(md5(param + "SALT"), Kuaidi100PushClient.callbackSign(param, "SALT"));
        assertTrue(Kuaidi100PushClient.signMatches(md5(param + "SALT"), md5(param + "SALT").toLowerCase()),
                "对方大小写不一也认");
        assertFalse(Kuaidi100PushClient.signMatches(md5(param + "SALT"), md5(param + "salt")));
    }

    @Test
    void subscribeResponsesAreClassified() throws Exception {
        Method parse = Kuaidi100PushClient.class.getDeclaredMethod("parseSubscribe", String.class);
        parse.setAccessible(true);
        LogisticsPushClient.SubscribeResult ok = (LogisticsPushClient.SubscribeResult) parse.invoke(null,
                "{\"result\":true,\"returnCode\":\"200\",\"message\":\"提交成功\"}");
        assertTrue(ok.accepted());
        LogisticsPushClient.SubscribeResult dup = (LogisticsPushClient.SubscribeResult) parse.invoke(null,
                "{\"result\":false,\"returnCode\":\"501\",\"message\":\"POLL:重复订阅\"}");
        assertTrue(dup.accepted(), "重复订阅算受理");
        LogisticsPushClient.SubscribeResult rejected = (LogisticsPushClient.SubscribeResult) parse.invoke(null,
                "{\"result\":false,\"returnCode\":\"701\",\"message\":\"拒绝订阅的单号\"}");
        assertFalse(rejected.accepted());
        assertTrue(rejected.permanent(), "单号被拒再试也没用");
        LogisticsPushClient.SubscribeResult badKey = (LogisticsPushClient.SubscribeResult) parse.invoke(null,
                "{\"result\":false,\"returnCode\":\"600\",\"message\":\"您不是合法的订阅者\"}");
        assertFalse(badKey.permanent(), "key 配错是整批的问题，改好配置之后这些运单还该订得上");

        Method build = Kuaidi100PushClient.class.getDeclaredMethod("buildSubscribeParam", String.class, String.class,
                String.class, String.class, String.class, String.class);
        build.setAccessible(true);
        String p = (String) build.invoke(null, "shunfeng", "SF1", "0759-1", "KEY", "https://x/cb?sid=1", "abc");
        assertTrue(p.contains("\"callbackurl\":\"https://x/cb?sid=1\"") && p.contains("\"salt\":\"abc\"")
                && p.contains("\"phone\":\"0759-1\"") && p.contains("\"resultv2\":\"1\""), p);
    }

    @Test
    void pushParamParsesStatusNumberAndNodes() {
        Kuaidi100PushClient.PushedCallback cb = Kuaidi100PushClient.parseCallback(
                pushParam("polling", "SF123", 5, T2, "派件中", T1, "已揽收"));
        assertEquals("polling", cb.status());
        assertEquals("SF123", cb.number());
        assertEquals(5, cb.result().state());
        assertEquals(T2, cb.result().latest().time());

        Kuaidi100PushClient.PushedCallback abort = Kuaidi100PushClient.parseCallback(abortParam("SF9", "3天查询无记录"));
        assertEquals("abort", abort.status());
        assertEquals("3天查询无记录", abort.message(), "中止原因要留下来给后台看");
    }

    @Test
    void wishShipmentsUseTheWishReceivingPhone() {
        DonateShipment wish = new DonateShipment();
        wish.setBizType(DonateFlow.BIZ_WISH);
        wish.setBizId(1L);
        assertEquals("0759-6666666", trackService.phoneFor(wish),
                "微心愿的包裹寄到办公室，顺丰要的收件电话就是微心愿配置里那个（此前传的是空）");
    }

    // ---------------- helpers ----------------

    private DonateFlowVOs.Shipment register() {
        return shipmentService.register(donor, campaign, shipment(expressNo(), item("书", DonateFlow.TYPE_BOOK, 1, null)));
    }

    private DonateFlowVOs.Shipment subscribedShipment() {
        DonateFlowVOs.Shipment s = register();
        pushService.subscribeDue();
        assertEquals(DonateFlow.SUBSCRIBE_ACTIVE, ((Number) row(s.getId()).get("subscribe_status")).intValue());
        return s;
    }

    private String noOf(Long id) {
        return jdbc.queryForObject("SELECT express_no FROM donate_shipment WHERE id = ?", String.class, id);
    }

    private Map<String, Object> row(Long id) {
        Map<String, Object> m = jdbc.queryForMap("SELECT subscribe_status, subscribe_salt, subscribe_attempts, "
                + "subscribe_error, track_last_context, track_done, push_time FROM donate_shipment WHERE id = ?", id);
        // track_last_time 单独按 LocalDateTime 取，免得驱动给的类型与断言对不上
        m.put("track_last_time", jdbc.queryForObject("SELECT track_last_time FROM donate_shipment WHERE id = ?",
                LocalDateTime.class, id));
        return m;
    }

    private void backdateAttempt(Long id) {
        jdbc.update("UPDATE donate_shipment SET subscribe_attempt_time = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(31), id);
    }

    static String md5(String s) {
        try {
            return HexFormat.of().withUpperCase()
                    .formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 快递100 推送的 param（resultv2 格式），最多两个节点，新的在前。 */
    static String pushParam(String status, String no, int state, LocalDateTime t1, String c1,
                            LocalDateTime t2, String c2) {
        StringBuilder data = new StringBuilder("[{\"time\":\"").append(fmt(t1)).append("\",\"context\":\"")
                .append(c1).append("\"}");
        if (t2 != null) {
            data.append(",{\"time\":\"").append(fmt(t2)).append("\",\"context\":\"").append(c2).append("\"}");
        }
        data.append(']');
        return "{\"status\":\"" + status + "\",\"billstatus\":\"got\",\"message\":\"\",\"lastResult\":{"
                + "\"message\":\"ok\",\"nu\":\"" + no + "\",\"ischeck\":\"0\",\"com\":\"shunfeng\","
                + "\"status\":\"200\",\"state\":\"" + state + "\",\"data\":" + data + "}}";
    }

    static String abortParam(String no, String reason) {
        return "{\"status\":\"abort\",\"billstatus\":\"\",\"message\":\"" + reason + "\",\"lastResult\":{"
                + "\"message\":\"" + reason + "\",\"nu\":\"" + no + "\",\"com\":\"shunfeng\",\"status\":\"200\","
                + "\"state\":\"0\",\"data\":[]}}";
    }

    private static String fmt(LocalDateTime t) {
        return t.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** 可编程的假快递100 订阅。 */
    static class FakePush implements LogisticsPushClient {
        record Call(String no, String phone, String callbackUrl, String salt) {
        }

        volatile boolean enabled = true;
        volatile long delayMs = 0;
        volatile SubscribeResult next = new SubscribeResult(true, false, "200", "提交成功");
        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        final java.util.Set<String> rejectPermanently = ConcurrentHashMap.newKeySet();
        final java.util.Set<String> failTransiently = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        void reset() {
            enabled = true;
            delayMs = 0;
            next = new SubscribeResult(true, false, "200", "提交成功");
            calls.clear();
            rejectPermanently.clear();
            failTransiently.clear();
            counts.clear();
        }

        int countFor(String no) {
            AtomicInteger n = counts.get(no);
            return n == null ? 0 : n.get();
        }

        Call lastFor(String no) {
            synchronized (calls) {
                for (int i = calls.size() - 1; i >= 0; i--) {
                    if (calls.get(i).no().equals(no)) {
                        return calls.get(i);
                    }
                }
            }
            return null;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public SubscribeResult subscribe(String companyCode, String expressNo, String phone, String callbackUrl,
                                         String salt) {
            counts.computeIfAbsent(expressNo, k -> new AtomicInteger()).incrementAndGet();
            calls.add(new Call(expressNo, phone, callbackUrl, salt));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failTransiently.contains(expressNo)) {
                throw new LogisticsClient.LogisticsException("模拟快递100 服务繁忙");
            }
            if (rejectPermanently.contains(expressNo)) {
                return new SubscribeResult(false, true, "701", "拒绝订阅的单号");
            }
            return next;
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakePush fakePushClient() {
            return new FakePush();
        }

        @Bean
        @Primary
        DonateTrackServiceTest.FakeClient fakeQueryClient() {
            return new DonateTrackServiceTest.FakeClient();
        }
    }
}
