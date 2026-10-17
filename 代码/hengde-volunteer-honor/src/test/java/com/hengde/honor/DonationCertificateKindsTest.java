package com.hengde.honor;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonateCertRefs;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.constant.DonationFlow;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.dto.BookCampaignSaveDTO;
import com.hengde.donate.dto.DonateItemInputDTO;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.dto.RecipientOrgSaveDTO;
import com.hengde.donate.dto.ShipmentCheckDTO;
import com.hengde.donate.dto.ShipmentRegisterDTO;
import com.hengde.donate.dto.WishDTOs;
import com.hengde.donate.service.BookCampaignService;
import com.hengde.donate.service.CrowdfundService;
import com.hengde.donate.service.DonateCertificateQueryService;
import com.hengde.donate.service.DonateItemService;
import com.hengde.donate.service.DonateMasterDataService;
import com.hengde.donate.service.DonateShipmentService;
import com.hengde.donate.service.DonationService;
import com.hengde.donate.service.PairProjectService;
import com.hengde.donate.service.PairService;
import com.hengde.donate.service.WishService;
import com.hengde.donate.vo.DonateFlowVOs;
import com.hengde.donate.vo.PairVOs;
import com.hengde.honor.constant.CertificateType;
import com.hengde.honor.dto.CertificateTemplateSaveDTO;
import com.hengde.honor.service.CertificateService;
import com.hengde.honor.service.CertificateTemplateService;
import com.hengde.honor.vo.CertificateVO;
import com.itextpdf.kernel.pdf.PdfDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 所有捐赠都有证书（协会 2026-10-16 答复五 乙-1 / 乙-2 / 甲-5）。
 *
 * <p>每一类都走<b>真实入口</b>（心愿实现、包裹核对、捐款到账 / 退款、结对成立），不直接调证书服务——
 * 只测证书服务证明不了「donate 真的在那一刻发了事件、honor 真的接上了」。
 * 证书正文那半句话断言在 {@code DonateCertificateQueryService.find} 的出参上（渲染器原样套印它）：结对写「结对成功」、
 * 不写金额（乙-1），只有到账的众筹捐款才写金额；每张都真的走一遍渲染，证明套印用的是捐赠样本。
 * （不从 PDF 里抽字：内置宋体 STSong-Light 没有嵌入 ToUnicode，抽出来的中文缺字。）</p>
 *
 * <p>注解与 {@code CertificateServiceTest} 一致，好复用同一个测试上下文。<b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, InMemoryFileStorageConfig.class})
class DonationCertificateKindsTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000L);
    private static final long ADMIN = 8803L;
    private static final String TEMPLATE_KEY = "certificate-template/donation-kinds.pdf";

    @Autowired
    private WishService wishService;
    @Autowired
    private DonateShipmentService shipmentService;
    @Autowired
    private DonateItemService itemService;
    @Autowired
    private DonateMasterDataService masterDataService;
    @Autowired
    private BookCampaignService campaignService;
    @Autowired
    private CrowdfundService crowdfundService;
    @Autowired
    private DonationService donationService;
    @Autowired
    private PairProjectService projectService;
    @Autowired
    private PairService pairService;
    @Autowired
    private CertificateService certificateService;
    @Autowired
    private DonateCertificateQueryService donationQueryService;
    @Autowired
    private CertificateTemplateService templateService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void donationTemplate() {
        InMemoryFileStorageConfig.resetHooks();
        InMemoryFileStorageConfig.OBJECTS.put(TEMPLATE_KEY, blankPdf());
        templateService.list().forEach(t -> templateService.delete(t.getId()));
        CertificateTemplateSaveDTO dto = new CertificateTemplateSaveDTO();
        dto.setKind("donation");
        dto.setName("捐赠证书样本");
        dto.setFileKey(TEMPLATE_KEY);
        templateService.create(dto, ADMIN);
    }

    @AfterEach
    void clearTemplates() {
        templateService.list().forEach(t -> templateService.delete(t.getId()));
    }

    // ================= 微心愿：已实现时出 =================

    @Test
    void wish_issuedWhenRealized_notBefore() {
        Long donor = volunteer("圆梦人");
        Long org = org();
        String title = "证书心愿-" + SEQ.incrementAndGet();
        Long wishId = wishService.create(wish(title, org), ADMIN);
        wishService.claim(wishId, donor);
        Long claimId = jdbc.queryForObject("SELECT id FROM donate_wish_claim WHERE wish_id = ? AND volunteer_id = ?",
                Long.class, wishId, donor);
        DonateFlowVOs.Shipment s = wishService.registerShipment(wishId, donor, shipment(item("书包", 1)));
        shipmentService.arrive(s.getId(), ADMIN);
        shipmentService.check(s.getId(), check(result(s.getItems().get(0).getId(), true, null)), ADMIN);
        assertNull(certRow(DonateCertRefs.wish(claimId)),
                "微心愿的包裹核对合格时不出证——那一类在「已实现」时按认领出，否则一个心愿会有两张");
        assertThrows(BusinessException.class, () -> certificateService.createForDonationRef(DonateCertRefs.wish(claimId)),
                "还没实现，人工补发也补不出来");

        itemService.generateCode(s.getItems().get(0).getId(), ADMIN);
        wishService.realize(wishId, List.of("https://cdn.example.com/f1.jpg"), ADMIN);

        Map<String, Object> row = certRow(DonateCertRefs.wish(claimId));
        assertNotNull(row, "心愿实现应自动出一张捐赠证书（事件 → 监听器 → createForDonationRef）");
        assertEquals(donor, ((Number) row.get("volunteer_id")).longValue());
        CertificateVO vo = mine(donor, "wish").get(0);
        assertEquals("wish", vo.getCategory());
        assertEquals("微心愿", vo.getCategoryLabel());
        assertEquals(title, vo.getSourceTitle());
        assertEquals(vo.getId(), certificateService.findMineByRef(donor, DonateCertRefs.wish(claimId)).getId());
        assertNull(certificateService.findMineByRef(volunteer("别人"), DonateCertRefs.wish(claimId)),
                "别人的与不存在一样拿不到");

        render(vo.getId(), donor);
        String text = statement(DonateCertRefs.wish(claimId));
        assertTrue(text.contains("微心愿") && text.contains(title), text);
    }

    // ================= 公益捐书 / 众筹捐物：核对出合格物资时出 =================

    @Test
    void bookAndCrowdfundGoods_issuedOnQualifiedCheck_onlyWhenSomethingQualified() {
        Long donor = volunteer("捐书人");
        String campaignTitle = "证书捐书-" + SEQ.incrementAndGet();
        Long campaign = openCampaign(campaignTitle);

        DonateFlowVOs.Shipment good = shipmentService.register(donor, campaign,
                shipment(item("课外书", 3), item("破书", 2)));
        shipmentService.arrive(good.getId(), ADMIN);
        Map<String, Long> ids = good.getItems().stream()
                .collect(Collectors.toMap(DonateFlowVOs.Item::getName, DonateFlowVOs.Item::getId));
        shipmentService.check(good.getId(), check(result(ids.get("课外书"), true, null),
                result(ids.get("破书"), false, "受潮")), ADMIN);
        assertNotNull(certRow(DonateCertRefs.book(good.getId())), "有合格物资：出一张");

        DonateFlowVOs.Shipment bad = shipmentService.register(donor, campaign, shipment(item("霉书", 1)));
        shipmentService.arrive(bad.getId(), ADMIN);
        shipmentService.check(bad.getId(), check(result(bad.getItems().get(0).getId(), false, "发霉")), ADMIN);
        assertNull(certRow(DonateCertRefs.book(bad.getId())), "全部不合格：不出（原型 P98「未审核的没有证书」的同一口径）");
        assertThrows(BusinessException.class, () -> certificateService.createForDonationRef(DonateCertRefs.book(bad.getId())));

        CertificateVO book = mine(donor, "book").get(0);
        assertEquals("公益捐书", book.getCategoryLabel());
        assertEquals(campaignTitle, book.getSourceTitle());
        render(book.getId(), donor);
        String text = statement(DonateCertRefs.book(good.getId()));
        assertTrue(text.contains(campaignTitle) && text.contains("共 3 件"), "件数只数合格的那 3 件：" + text);

        // 众筹捐物
        String cfTitle = "证书众筹物资-" + SEQ.incrementAndGet();
        Long cf = openGoodsCrowdfund(cfTitle);
        DonateFlowVOs.Shipment parcel = crowdfundService.registerGoods(cf, donor, shipment(item("书包", 2)));
        shipmentService.arrive(parcel.getId(), ADMIN);
        shipmentService.check(parcel.getId(), check(result(parcel.getItems().get(0).getId(), true, null)), ADMIN);
        assertNotNull(certRow(DonateCertRefs.crowdfundGoods(parcel.getId())));
        CertificateVO goods = mine(donor, "crowdfund").get(0);
        assertEquals("项目众筹", goods.getCategoryLabel());
        assertEquals(cfTitle, goods.getSourceTitle());
        assertEquals(1, mine(donor, "book").size(), "按分类筛：捐书页签里只有捐书那一张");
        assertEquals(2, mine(donor, null).size());
    }

    @Test
    void goodsCertificate_followsItemsAddedOrRemovedAfterTheCheck() {
        Long donor = volunteer("补录物资的捐书人");
        Long campaign = openCampaign("补录捐书-" + SEQ.incrementAndGet());
        // 核对时一件都不合格：没有证书
        DonateFlowVOs.Shipment s = shipmentService.register(donor, campaign, shipment(item("霉书", 1)));
        shipmentService.arrive(s.getId(), ADMIN);
        shipmentService.check(s.getId(), check(result(s.getItems().get(0).getId(), false, "发霉")), ADMIN);
        assertNull(certRow(DonateCertRefs.book(s.getId())));

        // 拆包时发现单子上漏写了一件、后台补录（已核对的包裹补录即合格）：补出证书（评审发现）
        Long added = shipmentService.addItem(s.getId(), item("课外书", 2), ADMIN);
        Map<String, Object> row = certRow(DonateCertRefs.book(s.getId()));
        assertNotNull(row, "补录了合格物资就该有证书");
        assertEquals(0, ((Number) row.get("is_deleted")).intValue());

        // 把这件合格物资又移除：一件合格的都不剩，证书撤销（留原因，可恢复）
        shipmentService.removeItem(added, ADMIN);
        Map<String, Object> revoked = certRow(DonateCertRefs.book(s.getId()));
        assertEquals(1, ((Number) revoked.get("is_deleted")).intValue(), "写着「经核对合格」的证书不能留着");
        assertTrue(revoked.get("deleted_reason").toString().contains("移除"), revoked.get("deleted_reason").toString());

        // 再补录一件：恢复原来那张（同一编号），不另发一张
        shipmentService.addItem(s.getId(), item("课外书", 1), ADMIN);
        Map<String, Object> back = certRow(DonateCertRefs.book(s.getId()));
        assertEquals(0, ((Number) back.get("is_deleted")).intValue());
        assertEquals(row.get("cert_no"), back.get("cert_no"));
    }

    // ================= 众筹捐款：到账时出，退款即撤 =================

    @Test
    void crowdfundMoney_issuedWhenPaid_revokedWhenRefunded() {
        Long donor = volunteer("捐款人");
        String cfTitle = "证书众筹捐款-" + SEQ.incrementAndGet();
        Long cf = openMoneyCrowdfund(cfTitle);
        long donationId = awaitingDonation(cf, donor, cfTitle, "88.50");
        assertNull(certRow(DonateCertRefs.crowdfundMoney(donationId)), "没到账不出");

        assertTrue(donationService.onPaid(DonationFlow.BIZ_CROWDFUND, String.valueOf(donationId), null));
        Map<String, Object> row = certRow(DonateCertRefs.crowdfundMoney(donationId));
        assertNotNull(row, "到账即出——事件在付款回调的事务之外发布，监听器靠 fallbackExecution 才接得到");
        render(((Number) row.get("id")).longValue(), donor);
        String text = statement(DonateCertRefs.crowdfundMoney(donationId));
        assertTrue(text.contains("88.5 元") && text.contains(cfTitle), "到账的钱才写金额：" + text);

        donationService.refund(donationId, "捐款人要求退回", ADMIN);
        Map<String, Object> revoked = certRow(DonateCertRefs.crowdfundMoney(donationId));
        assertEquals(1, ((Number) revoked.get("is_deleted")).intValue(), "钱退了，那张写着金额的证书不能还留着");
        assertTrue(revoked.get("deleted_reason").toString().contains("退款"), revoked.get("deleted_reason").toString());
    }

    // ================= 结对：结对成功时出，正文写结对成功、不写金额 =================

    @Test
    void pair_saysPairedNotPaid_andUsesTheDonationTemplateOnly() {
        Long donor = volunteer("结对人");
        String projectTitle = "证书结对-" + SEQ.incrementAndGet();
        Long projectId = openPairProject(projectTitle);
        PairVOs.PairRecord r = pairService.register(projectId, donor, register("300"));
        pairService.establish(r.getId(), ADMIN);
        Long certId = ((Number) certRow(DonateCertRefs.pair(r.getId())).get("id")).longValue();

        render(certId, donor);
        String text = statement(DonateCertRefs.pair(r.getId()));
        assertTrue(text.contains("结对成功") && text.contains(projectTitle), text);
        assertFalse(text.contains("300") || text.contains("金额") || text.contains("认捐"),
                "协会 10-16 乙-1：结对证书在结对成功时出、不是付款后——那时钱没到账，不能盖章写金额：" + text);

        // 只有活动证书的全局样本时：捐赠证书拒绝出证，不拿活动证书的样本凑（甲-5：样本分两种）
        Long other = volunteer("结对人二");
        PairVOs.PairRecord r2 = pairService.register(projectId, other, register("100"));
        pairService.establish(r2.getId(), ADMIN);
        Long cert2 = ((Number) certRow(DonateCertRefs.pair(r2.getId())).get("id")).longValue();
        templateService.list().forEach(t -> templateService.delete(t.getId()));
        String globalKey = "certificate-template/activity-only.pdf";
        InMemoryFileStorageConfig.OBJECTS.put(globalKey, blankPdf());
        CertificateTemplateSaveDTO activityOnly = new CertificateTemplateSaveDTO();
        activityOnly.setName("志愿活动样本");
        activityOnly.setFileKey(globalKey);
        templateService.create(activityOnly, ADMIN);
        BusinessException e = assertThrows(BusinessException.class,
                () -> certificateService.downloadUrlForVolunteer(cert2, other));
        assertTrue(e.getMessage().contains("捐赠证书的电子样本"), e.getMessage());

        // 捐赠样本不绑活动
        CertificateTemplateSaveDTO wrong = new CertificateTemplateSaveDTO();
        wrong.setKind("donation");
        wrong.setActivityId(1L);
        wrong.setName("错的");
        wrong.setFileKey(globalKey);
        assertThrows(BusinessException.class, () -> templateService.create(wrong, ADMIN));
        assertTrue(templateService.list().stream().allMatch(t -> "activity".equals(t.getKind())));
    }

    // ---------------- helpers ----------------

    private List<CertificateVO> mine(Long volunteerId, String category) {
        return certificateService.myCertificates(volunteerId, new PageQuery(), category).getRecords().stream()
                .filter(c -> CertificateType.DONATION == c.getType()).toList();
    }

    /** 走志愿者的下载入口真的渲染一次：套在捐赠样本上、写进存储。 */
    private void render(Long certId, Long volunteerId) {
        assertNotNull(certificateService.downloadUrlForVolunteer(certId, volunteerId));
        String key = jdbc.queryForObject("SELECT file_key FROM honor_certificate WHERE id = ?", String.class, certId);
        byte[] pdf = InMemoryFileStorageConfig.OBJECTS.get(key);
        assertTrue(pdf != null && pdf.length > 0, "渲染出的 PDF 应已写入存储");
    }

    /** 证书正文里「您于某日」之后的那半句。 */
    private String statement(String bizRef) {
        DonateCertificateQueryService.Subject s = donationQueryService.find(bizRef);
        assertNotNull(s, "应当够格出证：" + bizRef);
        return s.statement();
    }

    private Map<String, Object> certRow(String bizRef) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM honor_certificate WHERE type = ? AND biz_ref = ?", CertificateType.DONATION, bizRef);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long awaitingDonation(Long crowdfundId, Long donor, String title, String amount) {
        BigDecimal a = new BigDecimal(amount);
        jdbc.update("INSERT INTO donate_donation (donation_no, biz_type, project_id, volunteer_id, project_title, amount, "
                        + "amount_fen, status, pay_expire_time, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                "HDDCERT" + SEQ.incrementAndGet(), DonationFlow.BIZ_CROWDFUND, crowdfundId, donor, title, a,
                a.movePointRight(2).intValueExact(), DonationFlow.AWAITING_PAYMENT, LocalDateTime.now().plusMinutes(30));
        return jdbc.queryForObject("SELECT MAX(id) FROM donate_donation WHERE volunteer_id = ?", Long.class, donor);
    }

    private Long volunteer(String name) {
        String phone = String.format("139%08d", SEQ.incrementAndGet() % 100_000_000L);
        Volunteer v = new Volunteer();
        v.setOpenid("cert_kinds_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        v.setRealName(name);
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Long org() {
        RecipientOrgSaveDTO d = new RecipientOrgSaveDTO();
        d.setName("证书小学-" + SEQ.incrementAndGet());
        d.setOrgType(1);
        return masterDataService.createOrg(d, ADMIN);
    }

    private static WishDTOs.Save wish(String title, Long orgId) {
        WishDTOs.Save d = new WishDTOs.Save();
        d.setTitle(title);
        d.setContent("想要一个新书包");
        d.setStory("旧书包背带断了");
        d.setImageUrl("https://cdn.example.com/wish/" + SEQ.incrementAndGet() + ".jpg");
        d.setChildName("李小明");
        d.setChildGender(1);
        d.setChildAge(9);
        d.setChildSchool("雷州市第一小学");
        d.setChildGrade("三年级");
        d.setReportOrgId(orgId);
        return d;
    }

    private Long openCampaign(String title) {
        BookCampaignSaveDTO d = new BookCampaignSaveDTO();
        d.setTitle(title);
        d.setStartTime(LocalDateTime.now().minusDays(1));
        d.setEndTime(LocalDateTime.now().plusDays(10));
        d.setRecvPhone("0759-8888888");
        d.setRecvAddress("广东省雷州市某路 1 号");
        Long id = campaignService.create(d, ADMIN);
        campaignService.publish(id);
        return id;
    }

    private Long openGoodsCrowdfund(String title) {
        PairDTOs.CrowdfundSave d = new PairDTOs.CrowdfundSave();
        d.setTitle(title);
        d.setAcceptGoods(true);
        d.setRecvName("协会物资组");
        d.setRecvPhone("0759-8888888");
        d.setRecvAddress("雷州市某路 1 号");
        Long id = crowdfundService.create(d, ADMIN);
        crowdfundService.publish(id);
        return id;
    }

    private Long openMoneyCrowdfund(String title) {
        PairDTOs.CrowdfundSave d = new PairDTOs.CrowdfundSave();
        d.setTitle(title);
        d.setTargetAmount(new BigDecimal("5000"));
        Long id = crowdfundService.create(d, ADMIN);
        crowdfundService.publish(id);
        return id;
    }

    private Long openPairProject(String title) {
        PairDTOs.ProjectSave d = new PairDTOs.ProjectSave();
        d.setTitle(title);
        d.setProjectType(PairFlow.TYPE_STUDY);
        d.setTargetAmount(new BigDecimal("1000"));
        Long id = projectService.create(d, ADMIN);
        projectService.publish(id);
        return id;
    }

    private static PairDTOs.Register register(String amount) {
        PairDTOs.Register d = new PairDTOs.Register();
        d.setAmountType(PairFlow.AMOUNT_PARTIAL);
        d.setAmount(new BigDecimal(amount));
        return d;
    }

    private static DonateItemInputDTO item(String name, int qty) {
        DonateItemInputDTO d = new DonateItemInputDTO();
        d.setName(name);
        d.setItemType(DonateFlow.TYPE_BOOK);
        d.setQuantity(qty);
        return d;
    }

    private static ShipmentRegisterDTO shipment(DonateItemInputDTO... items) {
        ShipmentRegisterDTO d = new ShipmentRegisterDTO();
        d.setExpressCode("shunfeng");
        d.setExpressNo("SF" + System.nanoTime() + SEQ.incrementAndGet());
        d.setDonorOrg("用例单位");
        d.setItems(new ArrayList<>(List.of(items)));
        return d;
    }

    private static ShipmentCheckDTO.ItemResult result(Long itemId, boolean ok, String remark) {
        ShipmentCheckDTO.ItemResult r = new ShipmentCheckDTO.ItemResult();
        r.setItemId(itemId);
        r.setQualified(ok);
        r.setRemark(remark);
        return r;
    }

    private static ShipmentCheckDTO check(ShipmentCheckDTO.ItemResult... results) {
        ShipmentCheckDTO d = new ShipmentCheckDTO();
        d.setResults(new ArrayList<>(List.of(results)));
        return d;
    }

    private static byte[] blankPdf() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             PdfDocument pdf = new PdfDocument(new com.itextpdf.kernel.pdf.PdfWriter(out))) {
            pdf.addNewPage();
            pdf.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("造样本 PDF 失败", e);
        }
    }
}
