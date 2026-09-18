package com.hengde.honor;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.service.PairProjectService;
import com.hengde.donate.service.PairService;
import com.hengde.donate.vo.PairVOs;
import com.hengde.honor.constant.CertificateType;
import com.hengde.honor.service.CertificateService;
import com.hengde.honor.vo.CertificateVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 捐赠证书（xlsx Row 10「捐赠后需要自动生成证书」，V3 结对批）。
 *
 * <p>这条链路横跨两个模块：<b>donate 发事件、honor 出证</b>（依赖方向 honor → donate，
 * donate 不能反向调用）。用例走的是<b>真实入口</b> {@code PairService.establish}，
 * 而不是直接调证书服务——只测证书服务证明不了「事件真的连上了」。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class DonationCertificateTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final long ADMIN = 8802L;

    @Autowired
    private PairProjectService projectService;
    @Autowired
    private PairService pairService;
    @Autowired
    private CertificateService certificateService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void establishIssuesOneCertificate_andCancelRevokesIt() {
        Long projectId = openProject("捐赠证书-" + SEQ.incrementAndGet(), "500");
        Long donor = volunteer("出证结对人");
        PairVOs.PairRecord r = pairService.register(projectId, donor, register("300"));

        pairService.establish(r.getId(), ADMIN);

        Map<String, Object> cert = certRow(r.getId());
        assertNotNull(cert, "结对成立应自动出一张捐赠证书（事件 → 监听器 → createForDonation）");
        assertEquals(CertificateType.DONATION, ((Number) cert.get("type")).intValue());
        assertEquals(donor, ((Number) cert.get("volunteer_id")).longValue());
        assertEquals(0, ((Number) cert.get("is_deleted")).intValue());
        assertTrue(cert.get("cert_no") != null);
        assertTrue(cert.get("slot_id") == null && cert.get("activity_id") == null,
                "捐赠证书没有活动与场次——它的幂等靠 biz_ref 唯一键，不靠 uk_slot_cert");

        // 幂等：人工补发接口重复调用不会多出一张
        Long again = certificateService.createForDonation(r.getId());
        assertEquals(((Number) cert.get("id")).longValue(), again, "同一条结对登记只会有一张证书");
        assertEquals(1, certCount(r.getId()));

        // 「我的证书」里能看到它，且来源写的是结对项目名而不是活动名
        List<CertificateVO> mine = certificateService.myCertificates(donor, new PageQuery()).getRecords();
        CertificateVO vo = mine.stream().filter(x -> CertificateType.DONATION == x.getType()).findFirst().orElseThrow();
        assertEquals("pair:" + r.getId(), vo.getBizRef());
        assertTrue(vo.getSourceTitle().startsWith("捐赠证书-"), vo.getSourceTitle());
        assertTrue(vo.getActivityTitle() == null, "捐赠证书不占用活动名字段");

        // 取消已成立的结对 → 证书被撤销（软删、留原因，可恢复）
        pairService.cancel(r.getId(), "结对人反悔", ADMIN);
        Map<String, Object> revoked = certRow(r.getId());
        assertEquals(1, ((Number) revoked.get("is_deleted")).intValue(), "结对没了，证书不能还留着");
        assertTrue(revoked.get("deleted_reason").toString().contains("结对已取消"), revoked.get("deleted_reason").toString());
        assertTrue(certificateService.myCertificates(donor, new PageQuery()).getRecords().stream()
                .noneMatch(x -> CertificateType.DONATION == x.getType()), "撤销后志愿者端看不到");
    }

    @Test
    void certificateRefusesWhenPairIsNotEstablished() {
        Long projectId = openProject("未成立-" + SEQ.incrementAndGet(), "500");
        Long donor = volunteer("还没确认的人");
        PairVOs.PairRecord r = pairService.register(projectId, donor, register("100"));

        assertTrue(assertThrows(BusinessException.class, () -> certificateService.createForDonation(r.getId()))
                .getMessage().contains("尚未成立"), "没成立就发证＝给一件没发生的事盖章");
        assertEquals(0, certCount(r.getId()));
        assertThrows(BusinessException.class, () -> certificateService.createForDonation(999_999_999L));
    }

    /**
     * 没有电子样本时<b>拒绝渲染</b>，不退回自绘无章版式。
     *
     * <p>这是 V2 第 4 批定下的口径（公章在样本上），捐赠证书沿用——测试环境本就没有配样本，
     * 于是这条正好把「渲染路径接上了、且接的是拒绝而不是凑合」一起证明了。</p>
     */
    @Test
    void downloadWithoutTemplate_refusesInsteadOfDrawingAnUnsealedOne() {
        Long projectId = openProject("无样本-" + SEQ.incrementAndGet(), "200");
        Long donor = volunteer("要下载的人");
        PairVOs.PairRecord r = pairService.register(projectId, donor, register("200"));
        pairService.establish(r.getId(), ADMIN);
        Long certId = ((Number) certRow(r.getId()).get("id")).longValue();

        BusinessException e = assertThrows(BusinessException.class,
                () -> certificateService.downloadUrlForVolunteer(certId, donor));
        assertTrue(e.getMessage().contains("电子样本"), e.getMessage());
        assertEquals(0, ((Number) certRow(r.getId()).get("download_count")).intValue(),
                "没真的发出去就不该记一次下载");
    }

    // ---------- helpers ----------

    private Long openProject(String title, String target) {
        PairDTOs.ProjectSave d = new PairDTOs.ProjectSave();
        d.setTitle(title);
        d.setProjectType(PairFlow.TYPE_STUDY);
        d.setTargetAmount(new BigDecimal(target));
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

    private Long volunteer(String name) {
        Volunteer v = new Volunteer();
        v.setOpenid("cert_pair_" + System.nanoTime() + "_" + SEQ.incrementAndGet());
        v.setRealName(name);
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        volunteerMapper.insert(v);
        return v.getId();
    }

    private Map<String, Object> certRow(Long pairRecordId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM honor_certificate WHERE biz_ref = ?", "pair:" + pairRecordId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int certCount(Long pairRecordId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM honor_certificate WHERE biz_ref = ?",
                Integer.class, "pair:" + pairRecordId);
    }
}
