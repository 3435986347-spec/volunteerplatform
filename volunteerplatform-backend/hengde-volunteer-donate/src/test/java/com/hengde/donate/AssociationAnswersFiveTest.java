package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.DonationFlow;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.service.DonateMasterDataService;
import com.hengde.donate.service.DonationService;
import com.hengde.donate.service.PairProjectService;
import com.hengde.donate.service.PairService;
import com.hengde.donate.service.WishService;
import com.hengde.donate.vo.DonationVOs;
import com.hengde.donate.vo.PairVOs;
import com.hengde.donate.vo.WishVOs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static com.hengde.donate.BookDonationTestSupport.ADMIN;
import static com.hengde.donate.BookDonationTestSupport.next;
import static com.hengde.donate.BookDonationTestSupport.phone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协会 2026-10-16 答复五落地的三处捐赠改动：结对项目的受助人信息与活动动态（丙-2）、捐款发票文件（丁-5）、
 * 微心愿查看人与手机号（丁-7）。
 *
 * <p>注解与 {@code PairServiceTest} 一致，好复用同一个测试上下文。<b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class AssociationAnswersFiveTest {

    @Autowired
    private PairProjectService projectService;
    @Autowired
    private PairService pairService;
    @Autowired
    private DonationService donationService;
    @Autowired
    private WishService wishService;
    @Autowired
    private DonateMasterDataService masterDataService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    // ================= 丙-2 结对项目的受助人信息 =================

    @Test
    void pairBeneficiary_isEncrypted_maskedForEveryoneButTheEstablishedPairer() {
        Long org = BookDonationTestSupport.org(masterDataService);
        String orgName = jdbc.queryForObject("SELECT name FROM donate_recipient_org WHERE id = ?", String.class, org);
        PairDTOs.ProjectSave d = project("受助人信息-" + next());
        d.setBeneficiaryName("邝小明");
        d.setBeneficiaryGender(1);
        d.setBeneficiaryAge(10);
        d.setBeneficiarySchool("雷州市第一小学");
        d.setBeneficiaryGrade("四年级");
        d.setReportOrgId(org);
        Long id = projectService.create(d, ADMIN);
        projectService.publish(id);

        String stored = jdbc.queryForObject("SELECT beneficiary_name FROM donate_pair_project WHERE id = ?", String.class, id);
        assertNotEquals("邝小明", stored, "未成年人姓名密文落库");
        assertFalse(jdbc.queryForObject("SELECT beneficiary_school FROM donate_pair_project WHERE id = ?", String.class, id)
                .contains("第一小学"));

        PairVOs.Project admin = projectService.detailForAdmin(id);
        assertEquals("邝小明", admin.getBeneficiaryName());
        assertEquals("雷州市第一小学", admin.getBeneficiarySchool());
        assertEquals(org, admin.getReportOrgId());
        assertEquals(orgName, admin.getReportOrgName());

        Long stranger = volunteer("路人");
        PairVOs.Project seen = projectService.detailForVolunteer(id, stranger);
        assertEquals("邝**", seen.getBeneficiaryName(), "原型 P47：姓名打 *");
        assertEquals("雷州***", seen.getBeneficiarySchool());
        assertTrue(seen.getBeneficiaryMasked());
        assertEquals(1, seen.getBeneficiaryGender());
        assertEquals(10, seen.getBeneficiaryAge());
        assertEquals("四年级", seen.getBeneficiaryGrade());
        assertEquals(orgName, seen.getReportOrgName());
        assertNull(seen.getReportOrgId(), "主数据 id 只给后台编辑回填用");

        Long pairer = volunteer("结对人");
        PairVOs.PairRecord r = pairService.register(id, pairer, register("300"));
        assertEquals("邝**", projectService.detailForVolunteer(id, pairer).getBeneficiaryName(),
                "登记了但协会还没确认：仍打 *（可能被驳回）");
        pairService.establish(r.getId(), ADMIN);
        PairVOs.Project mine = projectService.detailForVolunteer(id, pairer);
        assertEquals("邝小明", mine.getBeneficiaryName(), "已成立结对的人看全名（与微心愿认领人同一处理）");
        assertFalse(mine.getBeneficiaryMasked());
        assertTrue(projectService.listForVolunteer(new PageQuery(), 0, pairer).getRecords().stream()
                .filter(p -> p.getId().equals(id)).allMatch(p -> "邝**".equals(p.getBeneficiaryName())),
                "列表一律打 *，全名只在详情里");

        // 修改时不填即清空（显式 set，updateById 跳 null 会清不掉）
        PairDTOs.ProjectSave cleared = project(admin.getTitle());
        projectService.update(id, cleared);
        PairVOs.Project after = projectService.detailForAdmin(id);
        assertNull(after.getBeneficiaryName());
        assertNull(after.getReportOrgName());

        PairDTOs.ProjectSave bad = project("错性别-" + next());
        bad.setBeneficiaryGender(3);
        assertThrows(BusinessException.class, () -> projectService.create(bad, ADMIN));
    }

    @Test
    void pairUpdates_arePublic_newestFirst_andHiddenWithDraftProjects() {
        Long id = projectService.create(project("动态-" + next()), ADMIN);
        PairDTOs.UpdateSave u = new PairDTOs.UpdateSave();
        u.setContent("九月助学金已发放");
        u.setImages(List.of("https://cdn.example.com/u1.jpg"));
        u.setHappenTime(LocalDateTime.now().minusDays(3));
        Long older = projectService.addUpdate(id, u, ADMIN);
        PairDTOs.UpdateSave u2 = new PairDTOs.UpdateSave();
        u2.setContent("十月助学金已发放");
        Long newer = projectService.addUpdate(id, u2, ADMIN);

        Long viewer = volunteer("看动态");
        assertThrows(BusinessException.class, () -> projectService.updatesForVolunteer(id, new PageQuery()),
                "草稿项目对志愿者等于不存在");
        projectService.publish(id);
        List<PairVOs.PairUpdate> list = projectService.updatesForVolunteer(id, new PageQuery()).getRecords();
        assertEquals(List.of(newer, older), list.stream().map(PairVOs.PairUpdate::getId).toList(), "发生时间倒序");
        assertEquals(List.of("https://cdn.example.com/u1.jpg"), list.get(1).getImages());
        projectService.deleteUpdate(older);
        assertEquals(1, projectService.updatesFor(id, new PageQuery()).getRecords().size());
        assertNotNull(viewer);
    }

    // ================= 丁-5 发票文件 =================

    @Test
    void invoiceFile_uploadedByFinance_downloadableByDonor_andShowsCompleted() {
        Long donor = volunteer("要发票");
        long withInvoice = paidDonation(donor, true);
        long noInvoice = paidDonation(donor, false);

        DonationVOs.Donation before = donationService.detailMine(withInvoice, donor);
        assertFalse(before.getCompleted());
        assertEquals("已到账，待开票", before.getProgressLabel());
        assertFalse(before.getInvoiceFileReady());
        assertEquals("发票还没有开出",
                assertThrows(BusinessException.class, () -> donationService.invoiceFileUrlMine(withInvoice, donor)).getMessage());
        assertTrue(donationService.detailMine(noInvoice, donor).getCompleted(), "不要发票的到账即完结");

        assertThrows(BusinessException.class, () -> donationService.uploadInvoiceFile(withInvoice,
                new MockMultipartFile("file", "x.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)), null, ADMIN),
                "只收 PDF 与图片");
        assertThrows(BusinessException.class, () -> donationService.uploadInvoiceFile(noInvoice, pdf(), null, ADMIN),
                "没要发票的不能传");

        donationService.uploadInvoiceFile(withInvoice, pdf(), "No.0001", ADMIN);
        DonationVOs.Donation after = donationService.detailMine(withInvoice, donor);
        assertTrue(after.getCompleted(), "协会 10-16 丁-5：发票传上去，订单显示已完结");
        assertEquals("已完结", after.getProgressLabel());
        assertEquals(DonationFlow.INVOICE_ISSUED, after.getInvoiceStatus());
        assertEquals("No.0001", after.getInvoiceNo());
        assertTrue(after.getInvoiceFileReady());
        String key = jdbc.queryForObject("SELECT invoice_file_key FROM donate_donation WHERE id = ?", String.class, withInvoice);
        assertTrue(key.startsWith("invoice/"), key);
        assertTrue(donationService.invoiceFileUrlMine(withInvoice, donor).contains(key), "短期签名地址指向那份私有文件");
        assertEquals("捐款记录不存在",
                assertThrows(BusinessException.class, () -> donationService.invoiceFileUrlMine(withInvoice, volunteer("别人")))
                        .getMessage(), "别人的与不存在同一句话");

        // 再传一次是替换（开错了重开），不填发票号保留原值
        donationService.uploadInvoiceFile(withInvoice, pdf(), null, ADMIN);
        assertNotEquals(key, jdbc.queryForObject("SELECT invoice_file_key FROM donate_donation WHERE id = ?", String.class,
                withInvoice));
        assertEquals("No.0001", donationService.detailMine(withInvoice, donor).getInvoiceNo());
    }

    // ================= 丁-7 微心愿查看人 =================

    @Test
    void wishViewers_recordWhoOpenedTheDetail_withThePhoneTheyUsed() {
        Long org = BookDonationTestSupport.org(masterDataService);
        Long wishId = wishService.create(WishTestSupport.wish("查看人-" + next(), "小红", org), ADMIN);
        String p = phone();
        Long viewer = BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, "看心愿的人", p, true);
        wishService.detailForVolunteer(wishId, viewer);
        wishService.detailForVolunteer(wishId, viewer);
        Long noPhone = WishTestSupport.volunteerWithoutPhone(volunteerMapper);
        assertThrows(BusinessException.class, () -> wishService.detailForVolunteer(wishId, noPhone),
                "没验手机号看不了，也就不留记录");

        List<WishVOs.Viewer> viewers = wishService.viewers(wishId, new PageQuery()).getRecords();
        assertEquals(1, viewers.size());
        WishVOs.Viewer v = viewers.get(0);
        assertEquals(viewer, v.getVolunteerId());
        assertEquals("看心愿的人", v.getVolunteerName());
        assertEquals(p, v.getPhone(), "后台看得到他查看时的手机号");
        assertEquals(2, v.getViewCount(), "再看只累加次数，一人一个心愿一行");
        assertFalse(jdbc.queryForObject("SELECT phone FROM donate_wish_viewer WHERE wish_id = ?", String.class, wishId)
                .contains(p), "号码密文落库");
    }

    // ---------------- helpers ----------------

    private Long volunteer(String name) {
        return BookDonationTestSupport.volunteer(volunteerMapper, cryptoUtil, name, phone(), true);
    }

    private static PairDTOs.ProjectSave project(String title) {
        PairDTOs.ProjectSave d = new PairDTOs.ProjectSave();
        d.setTitle(title);
        d.setProjectType(PairFlow.TYPE_STUDY);
        d.setTargetAmount(new BigDecimal("1000"));
        return d;
    }

    private static PairDTOs.Register register(String amount) {
        PairDTOs.Register d = new PairDTOs.Register();
        d.setAmountType(PairFlow.AMOUNT_PARTIAL);
        d.setAmount(new BigDecimal(amount));
        return d;
    }

    /** 直接落一笔已到账的众筹捐款（支付通道在测试里没开）。 */
    private long paidDonation(Long donor, boolean needInvoice) {
        jdbc.update("INSERT INTO donate_donation (donation_no, biz_type, project_id, volunteer_id, project_title, amount, "
                        + "amount_fen, status, paid_time, need_invoice, invoice_title, invoice_status, create_time) "
                        + "VALUES (?, ?, 1, ?, '发票测试', 100.00, 10000, ?, NOW(), ?, ?, ?, NOW())",
                "HDDINV" + next(), DonationFlow.BIZ_CROWDFUND, donor, DonationFlow.PAID, needInvoice ? 1 : 0,
                needInvoice ? "雷州某公司" : null, needInvoice ? DonationFlow.INVOICE_PENDING : DonationFlow.INVOICE_NONE);
        return jdbc.queryForObject("SELECT MAX(id) FROM donate_donation WHERE volunteer_id = ?", Long.class, donor);
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "发票.pdf", "application/pdf",
                "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".getBytes(StandardCharsets.ISO_8859_1));
    }
}
