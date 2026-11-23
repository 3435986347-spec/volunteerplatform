package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.dto.BarcodeCatalogSaveDTO;
import com.hengde.donate.dto.DonateItemQuery;
import com.hengde.donate.dto.RecipientOrgSaveDTO;
import com.hengde.donate.service.BookCampaignService;
import com.hengde.donate.service.DonateBoxService;
import com.hengde.donate.service.DonateItemService;
import com.hengde.donate.service.DonateMasterDataService;
import com.hengde.donate.service.DonateShipmentService;
import com.hengde.donate.vo.DonateFlowVOs;
import com.hengde.donate.vo.DonateItemExportRow;
import com.hengde.donate.vo.DonateItemRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.function.Consumer;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.check;
import static com.hengde.donate.BookDonationTestSupport.item;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static com.hengde.donate.BookDonationTestSupport.result;
import static com.hengde.donate.BookDonationTestSupport.shipment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 10 维搜索与导出（Row 17 F）、受赠单位与条码库两类主数据（V3 捐书批）。
 *
 * <p>领域模块测试上下文没有分页拦截器，{@code selectPage} 返回全部匹配行——所以每条搜索都带上
 * 「本用例独有」的条件（活动 id），只断言自己造的那几行。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class DonateSearchAndMasterDataTest {

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
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;

    @Test
    void everyOneOfTheTenDimensionsFindsTheItem() {
        Long campaign = BookDonationTestSupport.openCampaign(campaignService);
        String donorPhone = phone();
        String donorName = "搜索用例捐赠人" + BookDonationTestSupport.next();
        Long donor = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, donorName, donorPhone, true);
        String rawNo = BookDonationTestSupport.expressNo();
        String isbn = "978" + BookDonationTestSupport.next();
        DonateFlowVOs.Shipment s = shipmentService.register(donor, campaign, shipment(rawNo,
                item("窗边的小豆豆", DonateFlow.TYPE_BOOK, 3, isbn), item("跳绳", DonateFlow.TYPE_SPORTS, 1, null)));
        shipmentService.arrive(s.getId(), ADMIN);
        Long book = s.getItems().get(0).getId();
        Long rope = s.getItems().get(1).getId();
        shipmentService.check(s.getId(), check(result(book, true, null), result(rope, true, null)), ADMIN);
        String code = itemService.generateCode(book, ADMIN).getExclusiveCode();
        DonateFlowVOs.Box box = boxService.create(campaign, ADMIN);
        boxService.pack(box.getId(), code, ADMIN);

        assertFinds(campaign, book, q -> q.setBoxCode(box.getBoxCode().toLowerCase()));
        assertFinds(campaign, book, q -> q.setExclusiveCode(code));
        assertFinds(campaign, book, q -> q.setCatalogBarcode(isbn));
        assertFinds(campaign, book, q -> q.setDonorName(donorName.substring(2, 8)));
        assertFinds(campaign, book, q -> q.setDonorPhone(donorPhone));
        assertFinds(campaign, book, q -> q.setDonorOrg("用例"));
        assertFinds(campaign, book, q -> q.setItemName("小豆豆"));
        assertFinds(campaign, book, q -> q.setItemType(DonateFlow.TYPE_BOOK));
        assertFinds(campaign, book, q -> q.setExpressNo(rawNo.toLowerCase()));
        assertFinds(campaign, book, q -> q.setStatus(DonateFlow.ITEM_PACKED));

        DonateItemQuery nobody = new DonateItemQuery();
        nobody.setBizId(campaign);
        nobody.setDonorPhone("13000000001");
        assertTrue(itemService.search(nobody, new PageQuery()).getRecords().isEmpty(), "查无此人的电话 → 空结果");

        DonateItemQuery byCampaign = new DonateItemQuery();
        byCampaign.setBizId(campaign);
        List<DonateItemRow> rows = itemService.search(byCampaign, new PageQuery()).getRecords();
        assertEquals(2, rows.size());
        DonateItemRow bookRow = rows.stream().filter(r -> r.getId().equals(book)).findFirst().orElseThrow();
        assertEquals(box.getBoxCode(), bookRow.getBoxCode());
        assertEquals("已装箱", bookRow.getStatusLabel());
        assertEquals("课外书籍", bookRow.getItemTypeLabel());

        // 导出：每物资一行，电话明文、志愿者码链接、进度中文
        List<DonateItemExportRow> export = itemService.exportRows(byCampaign);
        assertEquals(2, export.size());
        DonateItemExportRow e = export.stream().filter(r -> "窗边的小豆豆".equals(r.getName())).findFirst().orElseThrow();
        assertEquals(donorPhone, e.getDonorPhone(), "导出是给线下联系用的，电话要明文");
        assertTrue(e.getVolunteerCodeUrl().contains(donorPhone));
        assertEquals("已装箱", e.getProgress());
        assertEquals(3, e.getQuantity());
        assertEquals(box.getBoxCode(), e.getBoxCode());
        assertEquals(code, e.getExclusiveCode());
    }

    @Test
    void recipientOrgNamesAreUniqueAmongLiveRowsOnly() {
        RecipientOrgSaveDTO d = new RecipientOrgSaveDTO();
        d.setName("唯一性用例学校-" + BookDonationTestSupport.next());
        Long id = masterDataService.createOrg(d, ADMIN);
        BusinessException dup = assertThrows(BusinessException.class, () -> masterDataService.createOrg(d, ADMIN));
        assertTrue(dup.getMessage().contains("已存在"));
        masterDataService.deleteOrg(id);
        // 软删行不占键：删了能重建（V31 active_scope_key 那一课）
        assertTrue(masterDataService.createOrg(d, ADMIN) > 0);

        d.setStatus(0);
        Long disabled = masterDataService.createOrg(named(d, "停用学校-" + BookDonationTestSupport.next()), ADMIN);
        BusinessException off = assertThrows(BusinessException.class, () -> masterDataService.requireEnabledOrg(disabled));
        assertTrue(off.getMessage().contains("停用"));
    }

    @Test
    void catalogLookupReturnsNullWhenUnknownAndBarcodesAreUnique() {
        String isbn = "978" + BookDonationTestSupport.next();
        BarcodeCatalogSaveDTO c = new BarcodeCatalogSaveDTO();
        c.setBarcode(" " + isbn + " ");
        c.setName("草房子");
        c.setSpec("曹文轩 / 人民文学出版社");
        masterDataService.createCatalog(c);

        assertEquals("草房子", masterDataService.lookup(isbn).getName(), "条码两端空白被规整");
        assertEquals("课外书籍", masterDataService.lookup(isbn).getItemTypeLabel(), "不填类型默认课外书籍");
        assertNull(masterDataService.lookup("978000000000"), "查不到返回 null——让捐赠人手填，这不是错误");
        BusinessException dup = assertThrows(BusinessException.class, () -> masterDataService.createCatalog(c));
        assertTrue(dup.getMessage().contains("已在条码库中"));
    }

    // ---------------- helpers ----------------

    private void assertFinds(Long campaign, Long expectedItemId, Consumer<DonateItemQuery> condition) {
        DonateItemQuery q = new DonateItemQuery();
        q.setBizId(campaign);
        condition.accept(q);
        List<DonateItemRow> rows = itemService.search(q, new PageQuery()).getRecords();
        assertTrue(rows.stream().anyMatch(r -> r.getId().equals(expectedItemId)),
                "搜索条件 " + q + " 应能搜到物资 " + expectedItemId + "，实际=" + rows.stream().map(DonateItemRow::getId).toList());
    }

    private static RecipientOrgSaveDTO named(RecipientOrgSaveDTO base, String name) {
        RecipientOrgSaveDTO d = new RecipientOrgSaveDTO();
        d.setName(name);
        d.setStatus(base.getStatus());
        return d;
    }
}
