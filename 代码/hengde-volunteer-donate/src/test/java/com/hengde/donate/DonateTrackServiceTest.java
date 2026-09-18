package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.logistics.Kuaidi100LogisticsClient;
import com.hengde.donate.logistics.LogisticsClient;
import com.hengde.donate.service.BookCampaignService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.expressNo;
import static com.hengde.donate.BookDonationTestSupport.item;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static com.hengde.donate.BookDonationTestSupport.shipment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物流轨迹查询（快递100，捐书批只做查询）。
 *
 * <p>快递100 客户端换成一个可编程的假实现：没有协会账号时，<b>我们自己的逻辑</b>——快照、刷新间隔、
 * 失败保留旧快照、终态停止、轮询集合——必须照样被完整测试。真实 HTTP 那一层只测签名与报文解析。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(properties = {"hengde.donate.logistics.refresh-minutes=30",
        "hengde.donate.logistics.poll-interval-minutes=60", "hengde.donate.logistics.track-max-days=30"})
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, DonateTrackServiceTest.FakeLogistics.class})
class DonateTrackServiceTest {

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
    private FakeClient fake;

    private Long donor;
    private Long campaign;

    @BeforeEach
    void reset() {
        fake.reset();
        campaign = BookDonationTestSupport.openCampaign(campaignService);
        donor = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "看物流的人", phone(), true);
    }

    @Test
    void disabledProviderStillShowsTheOwnTrailAndSaysSo() {
        fake.enabled = false;
        DonateFlowVOs.Shipment s = register();
        DonateFlowVOs.Track t = trackService.trackForDonor(s.getId(), donor);
        assertFalse(t.isAvailable());
        assertEquals("物流查询未开通", t.getMessage());
        assertEquals(0, fake.calls.get(), "未开通时一次都不该去查");
    }

    @Test
    void snapshotIsStoredAndReusedWithinTheRefreshWindow() {
        DonateFlowVOs.Shipment s = register();
        fake.next = new LogisticsClient.TrackResult(0, List.of(
                new LogisticsClient.TrackNode(LocalDateTime.of(2026, 9, 15, 10, 0), "快件已到达湛江转运中心"),
                new LogisticsClient.TrackNode(LocalDateTime.of(2026, 9, 14, 18, 0), "快件已揽收")), "{}");

        DonateFlowVOs.Track first = trackService.trackForDonor(s.getId(), donor);
        DonateFlowVOs.Track second = trackService.trackForDonor(s.getId(), donor);

        assertTrue(first.isAvailable());
        assertEquals(2, first.getNodes().size());
        assertEquals("快件已到达湛江转运中心", first.getLastContext());
        assertEquals("在途", first.getStateLabel());
        assertEquals(1, fake.calls.get(), "刷新间隔内第二次看不该再查——快递100 按次计费");
        assertEquals(2, second.getNodes().size(), "第二次返回的是落库的快照");
        assertEquals("0759-8888888", fake.lastPhone.get(), "顺丰等要收件人电话：寄往协会的包裹就是活动预留电话");
    }

    @Test
    void failureKeepsTheOldSnapshotAndMarksItStale() {
        DonateFlowVOs.Shipment s = register();
        fake.next = new LogisticsClient.TrackResult(1, List.of(
                new LogisticsClient.TrackNode(LocalDateTime.of(2026, 9, 14, 18, 0), "快件已揽收")), "{}");
        trackService.trackForDonor(s.getId(), donor);
        jdbc.update("UPDATE donate_shipment SET track_query_time = ? WHERE id = ?",
                LocalDateTime.now().minusHours(2), s.getId());
        fake.fail = true;

        DonateFlowVOs.Track t = trackService.trackForDonor(s.getId(), donor);

        assertTrue(t.isStale(), "外部不可用时返回旧快照并如实标注");
        assertEquals("快件已揽收", t.getLastContext());
        assertEquals(1, t.getNodes().size());
        LocalDateTime touched = jdbc.queryForObject("SELECT track_query_time FROM donate_shipment WHERE id = ?",
                LocalDateTime.class, s.getId());
        assertTrue(touched.isAfter(LocalDateTime.now().minusMinutes(5)),
                "失败也要记查询时间，否则查不到的单号每轮都排在队首");
    }

    @Test
    void terminalStateStopsPollingAndArrivalStopsItToo() {
        DonateFlowVOs.Shipment signed = register();
        DonateFlowVOs.Shipment arrived = register();
        DonateFlowVOs.Shipment moving = register();
        shipmentService.arrive(arrived.getId(), ADMIN);
        fake.next = new LogisticsClient.TrackResult(3, List.of(
                new LogisticsClient.TrackNode(LocalDateTime.now(), "已签收")), "{}");
        trackService.trackForAdmin(signed.getId(), true);
        assertEquals(1, trackDone(signed.getId()), "签收即终态");
        assertEquals(1, trackDone(arrived.getId()), "我们确认到货就不必再查");

        fake.next = new LogisticsClient.TrackResult(0, List.of(), "{}");
        fake.calls.set(0);
        fake.nos.clear();   // 上面 trackForAdmin 那一次查询已记进 nos，不清掉就会把它误当成「轮询又查了签收单」
        int polled = trackService.pollDue();

        assertTrue(polled >= 1, "在途的那单要被轮询");
        String movingNo = jdbc.queryForObject("SELECT express_no FROM donate_shipment WHERE id = ?", String.class, moving.getId());
        String signedNo = jdbc.queryForObject("SELECT express_no FROM donate_shipment WHERE id = ?", String.class, signed.getId());
        String arrivedNo = jdbc.queryForObject("SELECT express_no FROM donate_shipment WHERE id = ?", String.class, arrived.getId());
        assertTrue(fake.nos.contains(movingNo));
        assertFalse(fake.nos.contains(signedNo), "终态的单退出轮询集合");
        assertFalse(fake.nos.contains(arrivedNo), "已到货的单退出轮询集合");

        // 同一轮间隔内再跑一次：刚查过的不再查
        fake.nos.clear();
        trackService.pollDue();
        assertFalse(fake.nos.contains(movingNo), "未到轮询间隔的不再查");
    }

    @Test
    void shipmentsOlderThanTheLimitAreRetiredWithoutQuerying() {
        DonateFlowVOs.Shipment old = register();
        jdbc.update("UPDATE donate_shipment SET ship_time = ? WHERE id = ?", LocalDateTime.now().minusDays(40), old.getId());
        fake.next = new LogisticsClient.TrackResult(0, List.of(), "{}");
        String no = jdbc.queryForObject("SELECT express_no FROM donate_shipment WHERE id = ?", String.class, old.getId());

        trackService.pollDue();

        assertFalse(fake.nos.contains(no), "寄出超过上限天数的不再查——一个填错的单号不能被查一辈子");
        assertEquals(1, trackDone(old.getId()));
    }

    @Test
    void otherCompanyCannotBeTrackedAndLeavesThePollSetAtOnce() {
        var dto = shipment(expressNo(), item("书", DonateFlow.TYPE_BOOK, 1, null));
        dto.setExpressCode("other");
        DonateFlowVOs.Shipment s = shipmentService.register(donor, campaign, dto);
        assertEquals(1, trackDone(s.getId()), "「其他」快递一登记就退出轮询集合");
        DonateFlowVOs.Track t = trackService.trackForDonor(s.getId(), donor);
        assertFalse(t.isAvailable());
        assertTrue(t.getMessage().contains("暂不支持"));
    }

    // ---------------- 快递100 报文层：签名与解析 ----------------

    @Test
    void kuaidi100SignatureIsUpperHexMd5OfParamKeyCustomer() throws Exception {
        Method sign = Kuaidi100LogisticsClient.class.getDeclaredMethod("sign", String.class, String.class, String.class);
        sign.setAccessible(true);
        String s = (String) sign.invoke(null, "{\"com\":\"yuantong\"}", "KEY", "CUSTOMER");
        assertTrue(s.matches("[0-9A-F]{32}"), s);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        String expected = java.util.HexFormat.of().withUpperCase()
                .formatHex(md.digest("{\"com\":\"yuantong\"}KEYCUSTOMER".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(expected, s, "签名 = MD5(param + key + customer) 大写");
    }

    @Test
    void kuaidi100ResponsesParseIntoNodesEmptyOrErrors() throws Exception {
        Method parse = Kuaidi100LogisticsClient.class.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        LogisticsClient.TrackResult ok = (LogisticsClient.TrackResult) parse.invoke(null,
                "{\"message\":\"ok\",\"status\":\"200\",\"state\":\"3\",\"data\":["
                        + "{\"time\":\"2026-09-15 12:00:00\",\"context\":\"已签收\"},"
                        + "{\"time\":\"2026-09-14 08:00:00\",\"context\":\"已揽收\"}]}");
        assertEquals(3, ok.state());
        assertEquals("已签收", ok.latest().context());
        assertEquals(LocalDateTime.of(2026, 9, 15, 12, 0), ok.latest().time());

        LogisticsClient.TrackResult none = (LogisticsClient.TrackResult) parse.invoke(null,
                "{\"result\":false,\"returnCode\":\"500\",\"message\":\"查询无结果，请隔段时间再查\"}");
        assertTrue(none.nodes().isEmpty(), "「查无结果」（还没揽收）是正常结果，不是错误");

        Exception bad = assertThrows(Exception.class, () -> parse.invoke(null,
                "{\"result\":false,\"returnCode\":\"503\",\"message\":\"验证签名失败\"}"));
        assertTrue(bad.getCause() instanceof LogisticsClient.LogisticsException);
    }

    // ---------------- helpers ----------------

    private DonateFlowVOs.Shipment register() {
        return shipmentService.register(donor, campaign, shipment(expressNo(), item("书", DonateFlow.TYPE_BOOK, 1, null)));
    }

    private int trackDone(Long id) {
        return jdbc.queryForObject("SELECT track_done FROM donate_shipment WHERE id = ?", Integer.class, id);
    }

    /** 可编程的假快递100。 */
    static class FakeClient implements LogisticsClient {
        volatile boolean enabled = true;
        volatile boolean fail = false;
        volatile TrackResult next = new TrackResult(null, List.of(), "{}");
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> lastPhone = new AtomicReference<>();
        final List<String> nos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final List<Long> queriedNos = new java.util.ArrayList<>();

        void reset() {
            enabled = true;
            fail = false;
            next = new TrackResult(null, List.of(), "{}");
            calls.set(0);
            lastPhone.set(null);
            nos.clear();
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public TrackResult query(String companyCode, String expressNo, String phone) {
            calls.incrementAndGet();
            lastPhone.set(phone);
            nos.add(expressNo);
            if (fail) {
                throw new LogisticsException("模拟快递100 服务繁忙");
            }
            return next;
        }
    }

    @TestConfiguration
    static class FakeLogistics {
        @Bean
        @Primary
        FakeClient fakeLogisticsClient() {
            return new FakeClient();
        }
    }
}
