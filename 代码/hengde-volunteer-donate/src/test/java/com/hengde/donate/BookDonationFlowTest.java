package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.dto.ExpressDTO;
import com.hengde.donate.dto.ReturnAddressDTO;
import com.hengde.donate.service.BookCampaignService;
import com.hengde.donate.service.DonateBoxService;
import com.hengde.donate.service.DonateItemService;
import com.hengde.donate.service.DonateMasterDataService;
import com.hengde.donate.service.DonateScanService;
import com.hengde.donate.service.DonateShipmentService;
import com.hengde.donate.vo.DonateFlowVOs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.check;
import static com.hengde.donate.BookDonationTestSupport.expressNo;
import static com.hengde.donate.BookDonationTestSupport.item;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static com.hengde.donate.BookDonationTestSupport.result;
import static com.hengde.donate.BookDonationTestSupport.shipment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公益捐书的完整流程（Row 17 十步）与每一步的拒绝条件（V3 捐书批）。
 *
 * <p>每一步都同时看<b>运单 / 物资 / 箱子 / 轨迹</b>四处——只看状态码的用例，
 * 在「动作做了、轨迹没写」这类缺陷上永远是绿的，而轨迹对捐赠人公开是需求原文。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class BookDonationFlowTest {

    @Autowired
    private BookCampaignService campaignService;
    @Autowired
    private DonateMasterDataService masterDataService;
    @Autowired
    private DonateShipmentService shipmentService;
    @Autowired
    private DonateItemService itemService;
    @Autowired
    private DonateBoxService boxService;
    @Autowired
    private DonateScanService scanService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    private Long campaign;
    private Long donor;
    private String donorPhone;

    @BeforeEach
    void seed() {
        campaign = BookDonationTestSupport.openCampaign(campaignService);
        donorPhone = phone();
        donor = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "捐书人甲", donorPhone, true);
    }

    @Test
    void tenStepsEndToEnd() {
        // ① 报名看得到收件地址：收件人 = 我的名字，地址 = 预留地址 + 我的名字（Row 17 原文格式）
        DonateFlowVOs.Campaign detail = campaignService.detailForVolunteer(campaign, donor);
        assertEquals("捐书人甲", detail.getRecvName());
        assertTrue(detail.getRecvAddress().endsWith("捐书人甲"), detail.getRecvAddress());
        assertEquals("0759-8888888", detail.getRecvPhone());
        assertTrue(detail.getOpen());

        // ②–④ 录入物资 + 登记快递，一次提交；单号被规整为大写去空白
        String rawNo = expressNo();
        DonateFlowVOs.Shipment s = shipmentService.register(donor, campaign, shipment(rawNo,
                item("小王子", DonateFlow.TYPE_BOOK, 2, "9787020042494"),
                item("铅笔盒", DonateFlow.TYPE_STATIONERY, 1, null),
                item("破损的旧书", DonateFlow.TYPE_BOOK, 1, null)));
        assertEquals(rawNo.replace(" ", "").toUpperCase(), s.getExpressNo());
        assertEquals(DonateFlow.SHIPMENT_SHIPPED, s.getStatus());
        assertEquals(3, s.getItems().size());
        assertTrue(s.getItems().stream().allMatch(i -> i.getStatus() == DonateFlow.ITEM_PENDING));
        assertEquals(1, s.getTraces().size(), "登记寄出要留一条轨迹");
        Map<String, Long> ids = s.getItems().stream()
                .collect(Collectors.toMap(DonateFlowVOs.Item::getName, DonateFlowVOs.Item::getId));

        // ⑤ 扫码到货：扫的是快递单号 → 识别出包裹 → 确认到货
        DonateFlowVOs.ScanResult scan = scanService.resolve(rawNo);
        assertEquals("SHIPMENT", scan.getKind());
        assertEquals(s.getId(), scan.getId());
        shipmentService.arrive(s.getId(), ADMIN);
        assertEquals(1, jdbc.queryForObject("SELECT track_done FROM donate_shipment WHERE id = ?", Integer.class, s.getId()),
                "我们已收到，就不必再向快递100 问——退出轮询集合");
        BusinessException twice = assertThrows(BusinessException.class, () -> shipmentService.arrive(s.getId(), ADMIN));
        assertTrue(twice.getMessage().contains("确认到货"), "重复扫码要说清已于何时到货：" + twice.getMessage());

        // ⑥ 核对：两件合格、一件不合格（须写原因）
        shipmentService.check(s.getId(), check(
                result(ids.get("小王子"), true, null),
                result(ids.get("铅笔盒"), true, null),
                result(ids.get("破损的旧书"), false, "封面脱落无法使用")), ADMIN);
        DonateFlowVOs.Shipment checked = shipmentService.detailMine(s.getId(), donor);
        assertEquals(DonateFlow.SHIPMENT_CHECKED, checked.getStatus());
        assertEquals(DonateFlow.RETURN_AWAIT_ADDRESS, checked.getReturnStatus(), "有不合格就等捐赠人交退回地址");

        // ⑦–⑧ 专属码：合格的才发；重复点返回原码（已经贴在书上的标签不能作废）
        DonateFlowVOs.ItemLabel label = itemService.generateCode(ids.get("小王子"), ADMIN);
        assertTrue(label.getExclusiveCode().startsWith("HDI"));
        assertTrue(label.getBarcode().startsWith("data:image/png;base64,"));
        assertEquals("捐书人甲", label.getDonorName(), "标签上要有捐赠人与物品名");
        assertEquals(label.getExclusiveCode(), itemService.generateCode(ids.get("小王子"), ADMIN).getExclusiveCode());
        String pencilCode = itemService.generateCode(ids.get("铅笔盒"), ADMIN).getExclusiveCode();
        BusinessException rejectedCode = assertThrows(BusinessException.class,
                () -> itemService.generateCode(ids.get("破损的旧书"), ADMIN));
        assertTrue(rejectedCode.getMessage().contains("核对合格"), rejectedCode.getMessage());

        // ⑨ 装箱：扫专属码，一次一件
        DonateFlowVOs.Box box = boxService.create(campaign, ADMIN);
        assertTrue(box.getBoxCode().startsWith("HDB"));
        boxService.pack(box.getId(), label.getExclusiveCode().toLowerCase(), ADMIN);
        boxService.pack(box.getId(), pencilCode, ADMIN);
        BusinessException again = assertThrows(BusinessException.class,
                () -> boxService.pack(box.getId(), pencilCode, ADMIN));
        assertTrue(again.getMessage().contains("已经在这只箱子里"), again.getMessage());
        assertEquals("BOX", scanService.resolve(box.getBoxCode()).getKind());
        assertEquals(2, scanService.resolve(box.getBoxCode()).getBox().getItemCount());

        // ⑩ 送达受赠单位：箱内物资全部送达，单位名快照进物资
        Long org = BookDonationTestSupport.org(masterDataService);
        String orgName = jdbc.queryForObject("SELECT name FROM donate_recipient_org WHERE id = ?", String.class, org);
        DonateFlowVOs.Box delivered = boxService.deliver(box.getId(), org, ADMIN);
        assertEquals(DonateFlow.BOX_DELIVERED, delivered.getStatus());
        assertTrue(delivered.getItems().stream().allMatch(i -> i.getStatus() == DonateFlow.ITEM_DELIVERED
                && orgName.equals(i.getRecipientOrgName())));
        assertThrows(BusinessException.class, () -> boxService.deliver(box.getId(), org, ADMIN), "不能送达两次");

        // 不合格退回：捐赠人交地址 → 机构寄回
        ReturnAddressDTO addr = new ReturnAddressDTO();
        addr.setName("捐书人甲");
        addr.setPhone("13900001111");
        addr.setAddress("湛江市某小区 2 栋");
        shipmentService.submitReturnAddress(s.getId(), donor, addr);
        assertNotEquals13900001111InDb(s.getId());
        ExpressDTO back = new ExpressDTO();
        back.setExpressCode("zhongtong");
        back.setExpressNo("ZT" + BookDonationTestSupport.next());
        shipmentService.returnShip(s.getId(), back, ADMIN);
        DonateFlowVOs.Shipment done = shipmentService.detailMine(s.getId(), donor);
        assertEquals(DonateFlow.RETURN_SHIPPED, done.getReturnStatus());
        assertEquals("13900001111", done.getReturnPhone(), "本人看得到自己填的退回电话（明文）");
        assertEquals(DonateFlow.ITEM_RETURNED, statusOf(done, "破损的旧书"));

        // 我的捐书记录（Row 38）：受捐学校、审核状态
        List<DonateFlowVOs.Item> mine = itemService.listMine(donor, new PageQuery()).getRecords();
        DonateFlowVOs.Item book = mine.stream().filter(i -> "小王子".equals(i.getName())).findFirst().orElseThrow();
        assertEquals(orgName, book.getRecipientOrgName());
        assertEquals("合格", book.getAuditLabel());
        assertEquals(label.getExclusiveCode(), book.getExclusiveCode());
        assertEquals("9787020042494", book.getCatalogBarcode());
        assertEquals("不合格", mine.stream().filter(i -> "破损的旧书".equals(i.getName())).findFirst().orElseThrow()
                .getAuditLabel());

        // 全程轨迹对捐赠人公开：登记、到货、三件核对、两件生成码、两件装箱、两件送达、交地址、寄回
        List<Integer> actions = done.getTraces().stream().map(DonateFlowVOs.Trace::getAction).toList();
        assertEquals(1, count(actions, DonateFlow.ACT_REGISTER));
        assertEquals(1, count(actions, DonateFlow.ACT_ARRIVE));
        assertEquals(2, count(actions, DonateFlow.ACT_CHECK_PASS));
        assertEquals(1, count(actions, DonateFlow.ACT_CHECK_FAIL));
        assertEquals(2, count(actions, DonateFlow.ACT_CODE), "重复点生成码不得多记轨迹");
        assertEquals(2, count(actions, DonateFlow.ACT_PACK));
        assertEquals(2, count(actions, DonateFlow.ACT_DELIVER));
        assertEquals(1, count(actions, DonateFlow.ACT_RETURN_ADDRESS));
        assertEquals(1, count(actions, DonateFlow.ACT_RETURN_SHIP));

        // 本次活动数据（Row 17 C）：只算合格的
        DonateFlowVOs.CampaignStats stats = campaignService.detailForAdmin(campaign).getStats();
        assertEquals(1, stats.getParticipants());
        assertEquals(1, stats.getParcels());
        assertEquals(2, stats.getBooks(), "小王子 ×2 合格；破损旧书不合格不算「收到」");
        assertEquals(1, stats.getStationery());
        assertEquals(0, stats.getSports());
    }

    @Test
    void onlyRegisteredDonorsAndOnlyOpenCampaigns() {
        Long guest = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "游客", phone(), false);
        assertMessage("实名", () -> shipmentService.register(guest, campaign,
                shipment(expressNo(), item("书", DonateFlow.TYPE_BOOK, 1, null))));

        Long draft = campaignService.create(BookDonationTestSupport.campaignDto(), ADMIN);
        assertMessage("不在报名时间内", () -> shipmentService.register(donor, draft,
                shipment(expressNo(), item("书", DonateFlow.TYPE_BOOK, 1, null))));

        Long ended = BookDonationTestSupport.openCampaign(campaignService);
        campaignService.end(ended);
        assertMessage("不在报名时间内", () -> shipmentService.register(donor, ended,
                shipment(expressNo(), item("书", DonateFlow.TYPE_BOOK, 1, null))));
        assertEquals(2, campaignService.detailForVolunteer(ended, donor).getDisplayStatus(), "手动结束即显示已结束");
        assertMessage("活动不存在", () -> campaignService.detailForVolunteer(draft, donor));
    }

    @Test
    void anExpressNumberRegistersOnceUntilCancelled() {
        String no = expressNo();
        DonateFlowVOs.Shipment first = shipmentService.register(donor, campaign,
                shipment(no, item("书", DonateFlow.TYPE_BOOK, 1, null)));
        assertMessage("已经登记过", () -> shipmentService.register(donor, campaign,
                shipment(no.toUpperCase(), item("书", DonateFlow.TYPE_BOOK, 1, null))));

        shipmentService.cancel(first.getId(), donor);
        assertEquals(DonateFlow.ITEM_CANCELLED, jdbc.queryForObject(
                "SELECT status FROM donate_item WHERE shipment_id = ? LIMIT 1", Integer.class, first.getId()));
        // 取消释放单号：填错单号取消后重填，不该被自己卡死
        DonateFlowVOs.Shipment again = shipmentService.register(donor, campaign,
                shipment(no, item("书", DonateFlow.TYPE_BOOK, 1, null)));
        assertNotNull(again.getId());
    }

    @Test
    void cancelIsOnlyPossibleBeforeArrival() {
        DonateFlowVOs.Shipment s = shipmentService.register(donor, campaign,
                shipment(expressNo(), item("书", DonateFlow.TYPE_BOOK, 1, null)));
        shipmentService.arrive(s.getId(), ADMIN);
        assertMessage("不能再取消", () -> shipmentService.cancel(s.getId(), donor));
    }

    @Test
    void checkMustCoverExactlyThePendingItemsAndExplainRejections() {
        DonateFlowVOs.Shipment a = shipmentService.register(donor, campaign, shipment(expressNo(),
                item("A1", DonateFlow.TYPE_BOOK, 1, null), item("A2", DonateFlow.TYPE_BOOK, 1, null)));
        DonateFlowVOs.Shipment b = shipmentService.register(donor, campaign, shipment(expressNo(),
                item("B1", DonateFlow.TYPE_BOOK, 1, null)));
        assertMessage("不在待核对状态", () -> shipmentService.check(a.getId(),
                check(result(a.getItems().get(0).getId(), true, null)), ADMIN));
        shipmentService.arrive(a.getId(), ADMIN);
        Long a1 = a.getItems().get(0).getId();
        Long a2 = a.getItems().get(1).getId();
        Long b1 = b.getItems().get(0).getId();

        assertMessage("漏判", () -> shipmentService.check(a.getId(), check(result(a1, true, null)), ADMIN));
        assertMessage("不属于本包裹", () -> shipmentService.check(a.getId(),
                check(result(a1, true, null), result(a2, true, null), result(b1, true, null)), ADMIN));
        assertMessage("写明原因", () -> shipmentService.check(a.getId(),
                check(result(a1, true, null), result(a2, false, " ")), ADMIN));
        assertMessage("判两次", () -> shipmentService.check(a.getId(),
                check(result(a1, true, null), result(a1, false, "x")), ADMIN));
        assertEquals(DonateFlow.SHIPMENT_ARRIVED, jdbc.queryForObject(
                "SELECT status FROM donate_shipment WHERE id = ?", Integer.class, a.getId()), "被拒的核对整体回滚");

        shipmentService.check(a.getId(), check(result(a1, true, null), result(a2, true, null)), ADMIN);
        assertEquals(DonateFlow.RETURN_NONE, jdbc.queryForObject(
                "SELECT return_status FROM donate_shipment WHERE id = ?", Integer.class, a.getId()));
        assertMessage("不在待核对状态", () -> shipmentService.check(a.getId(),
                check(result(a1, true, null), result(a2, true, null)), ADMIN));
    }

    @Test
    void adminCanAddAnItemFoundInTheParcelAndItJoinsTheCheck() {
        DonateFlowVOs.Shipment s = shipmentService.register(donor, campaign, shipment(expressNo(),
                item("清单上的书", DonateFlow.TYPE_BOOK, 1, null)));
        assertMessage("已到货", () -> shipmentService.addItem(s.getId(),
                item("漏写的", DonateFlow.TYPE_BOOK, 1, null), ADMIN));
        shipmentService.arrive(s.getId(), ADMIN);
        Long extra = shipmentService.addItem(s.getId(), item("单子上漏写的跳绳", DonateFlow.TYPE_SPORTS, 1, null), ADMIN);
        assertEquals(DonateFlow.ITEM_ARRIVED, itemStatus(extra), "到货后补录的物资也要过核对");
        assertMessage("漏判", () -> shipmentService.check(s.getId(),
                check(result(s.getItems().get(0).getId(), true, null)), ADMIN));
        shipmentService.check(s.getId(), check(result(s.getItems().get(0).getId(), true, null),
                result(extra, true, null)), ADMIN);

        Long afterCheck = shipmentService.addItem(s.getId(), item("核对后又发现一本", DonateFlow.TYPE_BOOK, 1, null), ADMIN);
        assertEquals(DonateFlow.ITEM_QUALIFIED, itemStatus(afterCheck), "核对之后后台亲手补的直接算合格");
        assertEquals(ADMIN, jdbc.queryForObject("SELECT added_by FROM donate_item WHERE id = ?", Long.class, afterCheck));
    }

    @Test
    void removingItemsFollowsTheFlowAndUndoesAnEmptyReturn() {
        DonateFlowVOs.Shipment s = shipmentService.register(donor, campaign, shipment(expressNo(),
                item("好书", DonateFlow.TYPE_BOOK, 1, null), item("坏书", DonateFlow.TYPE_BOOK, 1, null)));
        shipmentService.arrive(s.getId(), ADMIN);
        Long good = s.getItems().get(0).getId();
        Long bad = s.getItems().get(1).getId();
        shipmentService.check(s.getId(), check(result(good, true, null), result(bad, false, "发霉")), ADMIN);

        String code = itemService.generateCode(good, ADMIN).getExclusiveCode();
        DonateFlowVOs.Box box = boxService.create(campaign, ADMIN);
        boxService.pack(box.getId(), code, ADMIN);
        assertMessage("不能删除", () -> shipmentService.removeItem(good, ADMIN));

        // 删掉唯一一件不合格的：退回流程随之撤销，捐赠人不必再交地址
        shipmentService.removeItem(bad, ADMIN);
        assertEquals(DonateFlow.RETURN_NONE, jdbc.queryForObject(
                "SELECT return_status FROM donate_shipment WHERE id = ?", Integer.class, s.getId()));
    }

    @Test
    void anotherDonorCannotSeeOrTouchMyParcel() {
        DonateFlowVOs.Shipment s = shipmentService.register(donor, campaign,
                shipment(expressNo(), item("书", DonateFlow.TYPE_BOOK, 1, null)));
        Long other = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "别人", phone(), true);
        assertMessage("运单不存在", () -> shipmentService.detailMine(s.getId(), other));
        assertMessage("运单不存在", () -> shipmentService.cancel(s.getId(), other));
    }

    @Test
    void publishRequiresReceiverInfoAndOnlyDraftsCanBeDeleted() {
        var d = BookDonationTestSupport.campaignDto();
        d.setRecvAddress(null);
        Long id = campaignService.create(d, ADMIN);
        assertMessage("收件电话与收件地址", () -> campaignService.publish(id));
        Long open = BookDonationTestSupport.openCampaign(campaignService);
        assertMessage("草稿", () -> campaignService.delete(open));
        campaignService.delete(id);
        assertMessage("活动不存在", () -> campaignService.detailForAdmin(id));
    }

    @Test
    void displayStatusFollowsTimeWithoutAnyJob() {
        var d = BookDonationTestSupport.campaignDto();
        d.setStartTime(LocalDateTime.now().plusDays(2));
        d.setEndTime(LocalDateTime.now().plusDays(5));
        Long future = campaignService.create(d, ADMIN);
        campaignService.publish(future);
        DonateFlowVOs.Campaign vo = campaignService.detailForVolunteer(future, donor);
        assertEquals(0, vo.getDisplayStatus());
        assertFalse(vo.getOpen(), "未开始的不能报名");
        jdbc.update("UPDATE donate_campaign SET start_time = ?, end_time = ? WHERE id = ?",
                LocalDateTime.now().minusDays(5), LocalDateTime.now().minusMinutes(1), future);
        assertEquals(2, campaignService.detailForVolunteer(future, donor).getDisplayStatus(),
                "过了结束时间即已结束——没有任何定时任务去翻状态");
        assertMessage("不在报名时间内", () -> shipmentService.register(donor, future,
                shipment(expressNo(), item("书", DonateFlow.TYPE_BOOK, 1, null))));
    }

    @Test
    void scanRecognisesCatalogBarcodesAndUnknownCodes() {
        var c = new com.hengde.donate.dto.BarcodeCatalogSaveDTO();
        String isbn = "978" + BookDonationTestSupport.next();
        c.setBarcode(isbn);
        c.setName("用例书名");
        masterDataService.createCatalog(c);
        assertEquals("CATALOG", scanService.resolve(isbn).getKind());
        assertEquals("用例书名", scanService.resolve(isbn).getCatalogName());
        assertEquals("UNKNOWN", scanService.resolve("NOPE" + BookDonationTestSupport.next()).getKind());
        assertEquals("UNKNOWN", scanService.resolve("HDI2345678923").getKind(), "形态合法但查无此码");
    }

    // ---------------- helpers ----------------

    private void assertNotEquals13900001111InDb(Long shipmentId) {
        String stored = jdbc.queryForObject("SELECT return_phone FROM donate_shipment WHERE id = ?", String.class, shipmentId);
        assertFalse(stored.contains("13900001111"), "退回电话必须密文存储");
    }

    private int itemStatus(Long itemId) {
        return jdbc.queryForObject("SELECT status FROM donate_item WHERE id = ?", Integer.class, itemId);
    }

    private static int statusOf(DonateFlowVOs.Shipment s, String name) {
        return s.getItems().stream().filter(i -> name.equals(i.getName())).findFirst().orElseThrow().getStatus();
    }

    private static long count(List<Integer> xs, int v) {
        return xs.stream().filter(x -> x == v).count();
    }

    private static void assertMessage(String fragment, org.junit.jupiter.api.function.Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertTrue(e.getMessage().contains(fragment), "期望报错含「" + fragment + "」，实际=「" + e.getMessage() + "」");
    }

    @SuppressWarnings("unused")
    private static void unused() {
        assertNull(null);
    }
}
