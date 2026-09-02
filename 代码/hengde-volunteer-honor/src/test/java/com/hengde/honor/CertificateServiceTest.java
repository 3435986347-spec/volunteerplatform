package com.hengde.honor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.service.ServiceRecordService;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.honor.config.HonorProperties;
import com.hengde.honor.constant.CertificateSource;
import com.hengde.honor.constant.CertificateType;
import com.hengde.honor.dao.HonorCertificateMapper;
import com.hengde.honor.dto.CertificateTemplateSaveDTO;
import com.hengde.honor.entity.HonorCertificate;
import com.hengde.honor.job.CertificateReconcileJob;
import com.hengde.honor.service.CertificateService;
import com.hengde.honor.service.CertificateTemplateService;
import com.hengde.honor.vo.CertificateVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 电子证书核心（第 4 批）验证。<b>需本机 Docker</b>（MySQL + Redis）。
 *
 * <p>除正常路径外，重点压住几个<b>只在异常/竞态下才现形</b>的点：事件丢失后的补偿、
 * 上传件与渲染件互相覆盖、删除与签发的竞态、逻辑删除与唯一键的冲突、未参加者被发证。
 * 这些正是「测试全绿」最容易漏过的地方。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class, InMemoryFileStorageConfig.class})
class CertificateServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000 * 1000);

    @Autowired
    private CertificateService certificateService;
    @Autowired
    private CertificateTemplateService templateService;
    @Autowired
    private CertificateReconcileJob reconcileJob;
    @Autowired
    private ServiceRecordService serviceRecordService;
    @Autowired
    private HonorCertificateMapper certificateMapper;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivitySlotMapper slotMapper;
    @Autowired
    private ActivityAttendanceMapper attendanceMapper;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private com.hengde.common.crypto.CryptoUtil cryptoUtil;
    @Autowired
    private javax.sql.DataSource dataSource;
    @Autowired
    private HonorProperties honorProperties;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * 存储钩子是<b>静态可变状态</b>，用完必须复位。
     *
     * <p>忘了复位的后果是最难查的一类：钩子会在<b>别的用例</b>里触发，
     * 失败信息还指向那个无辜的用例，且是否发生取决于 JUnit 的方法执行顺序。</p>
     */
    @AfterEach
    void resetStorageHooks() {
        InMemoryFileStorageConfig.resetHooks();
    }

    // ---------- ① 自动创建：一场次一张 ----------

    /**
     * 秘书部确认考勤 → 自动建证书；<b>同一活动的两个场次各得一张</b>。
     *
     * <p>协会 2026-07-30：「证书是根据他的<b>场次</b>来决定的，一场活动一个证书」。</p>
     */
    @Test
    void secretaryConfirm_createsOneCertificatePerSlot() {
        Long aid = insertActivity();
        Long morning = insertSlot(aid, "上午岗");
        Long afternoon = insertSlot(aid, "下午岗");
        Long vid = insertVolunteer(null);

        serviceRecordService.secretaryConfirm(insertPendingAttendance(aid, morning, vid), 900L);
        serviceRecordService.secretaryConfirm(insertPendingAttendance(aid, afternoon, vid), 900L);

        List<HonorCertificate> certs = certsOf(vid);
        assertEquals(2, certs.size(), "两个场次 = 两张证书");
        assertEquals(2, certs.stream().map(HonorCertificate::getSlotId).distinct().count(),
                "两张证书必须分属不同场次");
        assertTrue(certs.stream().allMatch(c -> c.getFileKey() == null),
                "权益先建、PDF 懒渲染——此刻不该有文件");
        assertTrue(certs.stream().allMatch(c -> CertificateType.ACTIVITY == c.getType()));
        assertTrue(certs.stream().allMatch(c -> CertificateSource.SYSTEM == c.getSource()));
    }

    /** 重复触发（秘书部重复确认、补录回放、异步重试）不得产生第二张。 */
    @Test
    void createForSlot_isIdempotent() {
        Fixture f = confirmedFixture();
        Long first = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);
        Long second = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);
        assertEquals(first, second, "同一场次重复创建应返回同一张");
        assertEquals(1, certsOf(f.volunteerId).size());
    }

    /**
     * <b>没有已确认参加记录的人不得发证。</b>
     *
     * <p>这道闸不能只放在某个入口：批量上传按文件名手机号匹配，一个错号就会给
     * 从没参加过这场活动的人发一张证书，而那人还能下载到本不属于他的内容。</p>
     */
    @Test
    void createForSlot_withoutConfirmedAttendance_isRejected() {
        Long aid = insertActivity();
        Long slot = insertSlot(aid, "岗位");
        Long vid = insertVolunteer(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> certificateService.createForSlot(vid, aid, slot), "没参加过就不该有证书");
        assertTrue(ex.getMessage().contains("参加记录"), "实际：" + ex.getMessage());

        // 只签到没经秘书部确认，同样不算——与时长榜/积分发放同一条口径
        insertPendingAttendance(aid, slot, vid);
        assertThrows(BusinessException.class, () -> certificateService.createForSlot(vid, aid, slot),
                "未经秘书部确认的考勤不构成发证依据");
    }

    /**
     * 软删后重跑取<b>「恢复原记录」</b>口径：复活原行，<b>保留原编号与原文件</b>，
     * 而不是另发一张新编号的。
     */
    @Test
    void createForSlot_afterSoftDelete_restoresOriginalRecord() {
        Fixture f = confirmedFixture();
        Long certId = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);
        String originalNo = certificateMapper.selectById(certId).getCertNo();

        certificateService.softDelete(certId, "误发", 900L);
        assertNull(certificateMapper.selectById(certId), "软删后常规查询应查不到");
        assertTrue(certificateService.myCertificates(f.volunteerId).isEmpty(), "我的证书不返回软删的");

        Long again = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);
        assertEquals(certId, again, "应复活原行，而不是新建");
        HonorCertificate restored = certificateMapper.selectById(certId);
        assertNotNull(restored, "恢复后常规查询能查到");
        assertEquals(originalNo, restored.getCertNo(), "编号必须是原来那个——这才是「同一张证书」");
        assertNull(restored.getDeletedReason(), "恢复后删除留痕应清空");
    }

    /**
     * <b>补偿扫描不得复活软删的证书</b>——软删是管理员的明确意思表示，
     * 定时任务不该悄悄把它撤销。「重跑复活」只适用于业务重新触发。
     */
    @Test
    void reconcile_doesNotResurrectSoftDeletedCertificate() {
        Fixture f = confirmedFixture();
        Long certId = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);
        certificateService.softDelete(certId, "确实要删", 900L);

        reconcileJob.reconcile();

        assertNull(certificateMapper.selectById(certId), "补偿扫描不该把它复活");
        // certsOf 走 @TableLogic，只返回未删的：这里必须是空，否则说明另建了一张绕过软删
        assertTrue(certsOf(f.volunteerId).isEmpty(), "不该另建一张绕过软删");
        HonorCertificate raw = certificateMapper.selectBySlotIncludeDeleted(
                CertificateType.ACTIVITY, f.volunteerId, f.slotId);
        assertEquals(certId, raw.getId(), "原行仍在，只是保持已删状态");
        assertEquals(1, raw.getIsDeleted(), "仍应是已删");
    }

    /**
     * <b>事件丢了，证书不能就此永久丢失。</b>
     *
     * <p>自动建证挂在进程内事件上，而秘书部确认的 CAS 是一次性的（0→1），
     * 事件一旦没投递成功就<b>再也不会触发第二次</b>。这里直接造出「已确认考勤但无证书」
     * 的状态（等价于事件丢失），验证补偿扫描能把它补上。</p>
     */
    @Test
    void reconcile_createsCertificateWhenEventWasLost() {
        Fixture f = confirmedFixture();   // 直插已确认考勤，绕过 secretaryConfirm，等价于事件丢失
        assertTrue(certsOf(f.volunteerId).isEmpty(), "前置：此刻确实没有证书");

        reconcileJob.reconcile();

        List<HonorCertificate> certs = certsOf(f.volunteerId);
        assertEquals(1, certs.size(), "补偿扫描应补出这张证书");
        assertEquals(f.slotId, certs.get(0).getSlotId());

        // 再扫一次不得重复发
        reconcileJob.reconcile();
        assertEquals(1, certsOf(f.volunteerId).size(), "补偿扫描必须幂等");
    }

    /**
     * <b>补偿扫描不得触及回看窗口之外的数据。</b>
     *
     * <p>没有时间下界会从库里第一条已确认考勤扫起：每小时一次全表扫描，
     * 而且第一次跑就会把窗口之外所有缺证书的考勤一并补发。
     * 定时补偿要解决的是「刚刚那条事件丢了」，本就只需要覆盖近期。</p>
     *
     * <p>协会 2026-08-11 已定：只对系统上线后开展的活动自动发证，历史活动不补发
     * （现存历史活动全部是测试数据，待确认清单「1-追」已关闭）。
     * 窗口之外确实需要补的，走
     * {@link #manualReconcile_scopedToActivity_backfillsBeyondLookbackWindow} 那条人工入口。</p>
     */
    @Test
    void reconcile_doesNotBackfillCertificatesForOldAttendance() {
        Fixture f = confirmedFixture();
        // 把确认时间推到回看窗口（默认 72h）之外，模拟一条历史记录
        ActivityAttendance old = attendanceMapper.selectOne(
                Wrappers.<ActivityAttendance>lambdaQuery()
                        .eq(ActivityAttendance::getSlotId, f.slotId)
                        .eq(ActivityAttendance::getVolunteerId, f.volunteerId));
        ActivityAttendance patch = new ActivityAttendance();
        patch.setId(old.getId());
        patch.setSecretaryTime(LocalDateTime.now().minusDays(30));
        attendanceMapper.updateById(patch);

        reconcileJob.reconcile();

        assertTrue(certsOf(f.volunteerId).isEmpty(),
                "30 天前确认的考勤属历史数据，补偿扫描不该擅自补发证书");
    }

    /** 显式的「撤销删除」入口。 */
    @Test
    void restore_undoesSoftDelete() {
        Fixture f = confirmedFixture();
        Long certId = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);
        certificateService.softDelete(certId, "误删", 900L);

        certificateService.restore(certId);
        assertNotNull(certificateMapper.selectById(certId));
        assertThrows(BusinessException.class, () -> certificateService.restore(certId),
                "未被删除的证书不能再恢复一次");
    }

    /**
     * 后台列表必须能看到已软删的证书，否则删完就再也找不回那个 id，
     * 「撤销删除」入口形同虚设。
     */
    @Test
    void adminList_canIncludeSoftDeleted_soRestoreIsReachable() {
        Fixture f = confirmedFixture();
        Long certId = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);
        certificateService.softDelete(certId, "误发", 900L);

        var hidden = certificateService.adminList(new PageQuery(), f.volunteerId, null, false);
        assertTrue(hidden.getRecords().isEmpty(), "缺省不返回已删的");

        var shown = certificateService.adminList(new PageQuery(), f.volunteerId, null, true);
        assertEquals(1, shown.getRecords().size(), "includeDeleted=true 才看得到");
        CertificateVO row = shown.getRecords().get(0);
        assertEquals(certId, row.getId());
        assertTrue(row.getDeleted(), "要能区分出这行是已删的");
        assertEquals("误发", row.getDeletedReason(), "删除原因要带出来供核对");
        assertNotNull(row.getDeletedTime());
    }

    // ---------- ② 懒渲染 + 短期签名 + 下载计数 ----------

    /**
     * 首次下载才渲染并回填 file_key；<b>第二次直接复用</b>，不重复渲染、不覆盖对象。
     * 两次都各计一次下载。
     */
    @Test
    void download_lazyRendersOnce_thenReusesFile_andCountsEachDownload() {
        ensureGlobalTemplate();
        Fixture f = confirmedFixture();
        Long certId = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);
        assertNull(certificateMapper.selectById(certId).getFileKey(), "渲染前无文件");

        String url1 = certificateService.downloadUrlForVolunteer(certId, f.volunteerId);
        assertTrue(url1.startsWith("https://"), "返回的是签名 URL");
        HonorCertificate afterFirst = certificateMapper.selectById(certId);
        assertNotNull(afterFirst.getFileKey(), "首次下载应回填 file_key");
        assertNotNull(afterFirst.getGenerateTime());
        String key = afterFirst.getFileKey();
        assertTrue(key.contains("/system/"), "系统渲染件应落在 system 前缀下，与上传件分开");
        assertTrue(InMemoryFileStorageConfig.OBJECTS.containsKey(key), "对象已写入存储");
        assertEquals(1, InMemoryFileStorageConfig.WRITE_COUNT.get(key), "只渲染写入了一次");
        assertEquals(1, certificateMapper.selectById(certId).getDownloadCount());

        certificateService.downloadUrlForVolunteer(certId, f.volunteerId);
        assertEquals(1, InMemoryFileStorageConfig.WRITE_COUNT.get(key),
                "第二次下载必须复用已有文件，不得重新渲染覆盖");
        assertEquals(2, certificateMapper.selectById(certId).getDownloadCount(),
                "每次下载都要计数");
    }

    /**
     * <b>删除与签发的竞态</b>：拿到证书对象后、交出签名 URL 前，管理员把它删了。
     *
     * <p>渲染要几百毫秒，这个窗口是真实的。签名 URL 一旦交出去，
     * 在有效期内谁拿到都能下载，撤不回来，所以必须在签发前最后确认一次。</p>
     */
    @Test
    void download_afterSoftDelete_isRejectedAndNotCounted() {
        ensureGlobalTemplate();
        Fixture f = confirmedFixture();
        Long certId = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);
        certificateService.downloadUrlForVolunteer(certId, f.volunteerId);   // 先渲染好
        int countBefore = certificateMapper.selectByIdIncludeDeleted(certId).getDownloadCount();

        certificateService.softDelete(certId, "撤销发放", 900L);

        assertThrows(BusinessException.class,
                () -> certificateService.downloadUrlForVolunteer(certId, f.volunteerId),
                "已撤销的证书不得再签出下载链接");
        assertEquals(countBefore, certificateMapper.selectByIdIncludeDeleted(certId).getDownloadCount(),
                "被拒的请求不该计入下载次数");
    }

    /**
     * <b>未配置任何电子样本时拒绝生成</b>——不能退化成出一张无章的证书，
     * 那比没有更糟：志愿者会拿着一张不被承认的 PDF 以为自己有证书了。
     */
    @Test
    void download_withoutAnyTemplate_isRejected() {
        clearTemplates();
        Fixture f = confirmedFixture();
        Long certId = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> certificateService.downloadUrlForVolunteer(certId, f.volunteerId));
        assertTrue(ex.getMessage().contains("电子样本"), "报错要指明缺的是样本，实际：" + ex.getMessage());
        assertNull(certificateMapper.selectById(certId).getFileKey(), "拒绝后不得留下半成品文件");
    }

    /**
     * 样本记录在、<b>样本文件却读不到</b>时同样拒绝——公章在那份底图上，
     * 取不到底图就出不了「盖章的」证书，不能退回自绘无章版式。
     */
    @Test
    void download_whenTemplateFileMissing_isRejected() {
        clearTemplates();
        CertificateTemplateSaveDTO dto = new CertificateTemplateSaveDTO();
        dto.setName("指向不存在文件的样本");
        dto.setFileKey("certificate-template/missing-" + SEQ.incrementAndGet() + ".pdf");
        templateService.create(dto, 900L);

        Fixture f = confirmedFixture();
        Long certId = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);

        assertThrows(BusinessException.class,
                () -> certificateService.downloadUrlForVolunteer(certId, f.volunteerId));
        assertNull(certificateMapper.selectById(certId).getFileKey(), "失败后不得留下 file_key");
    }

    /** 越权：拿别人的证书 id 下载必须失败，且不能泄露「这张证书是否存在」。 */
    @Test
    void download_othersCertificate_isRejected() {
        ensureGlobalTemplate();
        Fixture f = confirmedFixture();
        Long intruder = insertVolunteer(null);
        Long certId = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> certificateService.downloadUrlForVolunteer(certId, intruder));
        assertEquals("证书不存在", ex.getMessage(),
                "不得区分「不存在」与「不是你的」，否则可枚举他人证书");
    }

    // ---------- ③ 按活动的电子样本 ----------

    /** 活动专属样本优先于全局默认（Row 36 F「设置<b>某个活动</b>的电子样本」）。 */
    @Test
    void template_activityScopedTakesPrecedenceOverGlobal() {
        ensureGlobalTemplate();
        Long aid = insertActivity();
        CertificateTemplateSaveDTO dto = new CertificateTemplateSaveDTO();
        dto.setActivityId(aid);
        dto.setName("专属样本");
        dto.setFileKey("certificate-template/act.pdf");
        Long tid = templateService.create(dto, 900L);
        assertNotNull(tid);

        // 同一作用域不许重复建——覆盖会让此前按该样本发的证书与当前配置对不上且无痕迹
        assertThrows(BusinessException.class, () -> templateService.create(dto, 900L));

        assertTrue(templateService.list().stream()
                        .anyMatch(t -> aid.equals(t.getActivityId()) && "专属样本".equals(t.getName())),
                "列表应能按活动解析出 activityId");
    }

    /**
     * <b>删掉样本后必须能在同一作用域重建。</b>
     *
     * <p>样本是逻辑删除。若唯一键建在裸 {@code scope_key} 上，软删行会继续占位：
     * 管理员删掉后想重建会收到「该作用域已存在样本，请直接修改它」，
     * 而列表里根本看不到那条（已软删）——既建不了也改不了，界面上彻底死锁。
     * 故 V31 的唯一键建在「仅未删行」的生成列上。</p>
     */
    @Test
    void template_canBeRecreatedInSameScopeAfterDeletion() {
        Long aid = insertActivity();
        CertificateTemplateSaveDTO dto = new CertificateTemplateSaveDTO();
        dto.setActivityId(aid);
        dto.setName("先建后删");
        dto.setFileKey("certificate-template/a.pdf");
        Long first = templateService.create(dto, 900L);

        templateService.delete(first);
        assertTrue(templateService.list().stream().noneMatch(t -> aid.equals(t.getActivityId())),
                "删除后列表里不该还有它");

        CertificateTemplateSaveDTO again = new CertificateTemplateSaveDTO();
        again.setActivityId(aid);
        again.setName("重建");
        again.setFileKey("certificate-template/b.pdf");
        Long second = templateService.create(again, 900L);
        assertNotEquals(first, second, "应当建出一条新的");
    }

    /**
     * <b>样本文件必须有私有上传入口。</b>
     *
     * <p>`fileKey` 要的是私有对象 key，而既有的 `/a/files/upload` 走公共读并返回 URL——
     * 没有这个入口，管理员根本造不出一个合法的 fileKey，样本管理四个接口全是摆设、
     * 证书也永远生成不出来。</p>
     */
    @Test
    void template_fileCanBeUploadedToPrivateStorage_andIsUsableAsFileKey() {
        clearTemplates();
        var upload = new org.springframework.mock.web.MockMultipartFile(
                "file", "样本.pdf", "application/pdf", samplePdf());
        String key = templateService.uploadTemplateFile(upload);
        assertNotNull(key);
        assertTrue(key.startsWith("certificate-template/"), "应落在样本目录下，实际：" + key);
        assertTrue(InMemoryFileStorageConfig.OBJECTS.containsKey(key), "应真的写进了私有存储");

        CertificateTemplateSaveDTO dto = new CertificateTemplateSaveDTO();
        dto.setName("上传得到的样本");
        dto.setFileKey(key);
        templateService.create(dto, 900L);

        // 用它真的能出证书——这一步才证明「上传入口 → fileKey → 渲染底图」整条链路是通的
        Fixture f = confirmedFixture();
        Long certId = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);
        assertNotNull(certificateService.downloadUrlForVolunteer(certId, f.volunteerId));
    }

    /** 作用域不可改，但**不能静默忽略**——前端改了没反应，管理员会以为已经挪走了。 */
    @Test
    void template_updateWithDifferentScope_isRejectedNotSilentlyIgnored() {
        Long aid = insertActivity();
        CertificateTemplateSaveDTO dto = new CertificateTemplateSaveDTO();
        dto.setActivityId(aid);
        dto.setName("原样本");
        dto.setFileKey("certificate-template/x.pdf");
        Long id = templateService.create(dto, 900L);

        CertificateTemplateSaveDTO moved = new CertificateTemplateSaveDTO();
        moved.setActivityId(insertActivity());   // 换了个活动
        moved.setName("想挪走");
        moved.setFileKey("certificate-template/x.pdf");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> templateService.update(id, moved));
        assertTrue(ex.getMessage().contains("作用域"), "实际：" + ex.getMessage());

        // 同作用域的普通修改仍然放行
        CertificateTemplateSaveDTO rename = new CertificateTemplateSaveDTO();
        rename.setActivityId(aid);
        rename.setName("改个名");
        rename.setFileKey("certificate-template/y.pdf");
        templateService.update(id, rename);
        assertTrue(templateService.list().stream().anyMatch(t -> "改个名".equals(t.getName())));
    }

    /**
     * <b>fileKey 只收上传接口产出的 key。</b>
     *
     * <p>与「不收前端传来的 scopeKey」同一条理由：随手写的字符串要等到<b>那个活动第一次出证</b>
     * 才报「文件读取失败」，而那时是志愿者在点下载，不是管理员在配置界面。</p>
     */
    @Test
    void template_fileKeyNotFromUploadEndpoint_isRejected() {
        clearTemplates();
        for (String bad : new String[]{"随手写的", "https://cdn.example/公章.pdf", "uploads/x.pdf"}) {
            CertificateTemplateSaveDTO dto = new CertificateTemplateSaveDTO();
            dto.setName("非法 key");
            dto.setFileKey(bad);
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> templateService.create(dto, 900L), "不该接受：" + bad);
            assertTrue(ex.getMessage().contains("certificate-template/"),
                    "报错要指明该用上传接口，实际：" + ex.getMessage());
        }
    }

    /**
     * <b>layout 必须能被清空。</b>
     *
     * <p>MP 的 {@code updateById} 会跳过值为 null 的字段，于是 layout 一经设置就再也清不掉，
     * 前端清空坐标配置保存后毫无反应。这正是 CLAUDE.md 里记着的 MedalService 同款教训，
     * 故改用 UpdateWrapper 显式 set。</p>
     */
    @Test
    void template_layoutCanBeCleared_notSkippedAsNull() {
        clearTemplates();
        CertificateTemplateSaveDTO dto = new CertificateTemplateSaveDTO();
        dto.setName("带坐标的样本");
        dto.setFileKey("certificate-template/layout.pdf");
        dto.setLayout("{\"name\":{\"x\":100,\"y\":200}}");
        Long id = templateService.create(dto, 900L);
        assertNotNull(templateService.list().get(0).getLayout(), "前置：坐标已存进去");

        CertificateTemplateSaveDTO cleared = new CertificateTemplateSaveDTO();
        cleared.setName("带坐标的样本");
        cleared.setFileKey("certificate-template/layout.pdf");
        cleared.setLayout(null);
        templateService.update(id, cleared);

        assertNull(templateService.list().get(0).getLayout(), "清空 layout 必须真的写进库");
    }

    /** 已被删除的样本不能再改——修改必须查影响行数，0 行不得报「修改成功」。 */
    @Test
    void template_updateAfterDeletion_isRejected() {
        clearTemplates();
        CertificateTemplateSaveDTO dto = new CertificateTemplateSaveDTO();
        dto.setName("待删样本");
        dto.setFileKey("certificate-template/gone.pdf");
        Long id = templateService.create(dto, 900L);
        templateService.delete(id);

        assertThrows(BusinessException.class, () -> templateService.update(id, dto),
                "已删的样本不该能改，更不该静默返回成功");
    }

    /**
     * 活动侧事实缺失时<b>拒绝出证</b>，而不是渲染一张姓名与活动都空着的「正式证书」——
     * 那种东西有编号、有公章底图，看着完全正规，却证明不了任何事。
     */
    @Test
    void download_whenAttendanceGone_refusesToRenderBlankCertificate() {
        ensureGlobalTemplate();
        Fixture f = confirmedFixture();
        Long certId = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);

        attendanceMapper.delete(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getSlotId, f.slotId)
                .eq(ActivityAttendance::getVolunteerId, f.volunteerId));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> certificateService.downloadUrlForVolunteer(certId, f.volunteerId));
        assertTrue(ex.getMessage().contains("参加记录"), "实际：" + ex.getMessage());
        assertNull(certificateMapper.selectById(certId).getFileKey(), "不得留下半成品文件");
        assertEquals(0, certificateMapper.selectById(certId).getDownloadCount(),
                "渲染失败不该计入下载次数");
    }

    // ---------- ④ 批量上传：逐条成败独立 ----------

    /** 一个能匹配且参加过、一个匹配不上 → 1 成 2 败，<b>不整批失败</b>。 */
    @Test
    void batchUpload_reportsPerRowOutcome_withoutFailingWholeBatch() {
        String phone = uniquePhone();
        Long vid = insertVolunteer(phone);
        Fixture f = confirmedFixtureFor(vid);

        var ok = mockPdf(phone + "_张三.pdf");
        var unmatched = mockPdf("19900000000.pdf");
        var noPhone = mockPdf("证书.pdf");

        var result = certificateService.batchUpload(f.activityId, f.slotId, List.of(ok, unmatched, noPhone), 900L);
        assertEquals(3, result.getTotal());
        assertEquals(1, result.getSucceeded());
        assertEquals(2, result.getFailed());

        var okRow = result.getRows().stream().filter(r -> r.getFileName().startsWith(phone)).findFirst().orElseThrow();
        assertTrue(okRow.isSuccess());
        assertNotNull(okRow.getCertificateId());
        var noPhoneRow = result.getRows().stream().filter(r -> "证书.pdf".equals(r.getFileName())).findFirst().orElseThrow();
        assertTrue(noPhoneRow.getError().contains("手机号"), "失败原因要说清是文件名里没手机号");

        HonorCertificate cert = certificateMapper.selectById(okRow.getCertificateId());
        assertNotNull(cert.getFileKey(), "上传的文件应挂上");
        assertTrue(cert.getFileKey().contains("/upload/"), "上传件应落在 upload 前缀下，与渲染件分开");
        assertEquals(CertificateSource.ADMIN_UPLOAD, cert.getSource(),
                "来源应改为后台上传——此后不参与懒渲染，避免系统样本覆盖协会原件");
        assertEquals(vid, cert.getVolunteerId());
    }

    /**
     * <b>文件名手机号写成了另一个真实用户</b>——那人没参加这场活动，必须整条失败，
     * 不能凭空给他发一张证书（他还能下载到本不属于他的内容）。
     */
    @Test
    void batchUpload_phoneOfNonParticipant_failsThatRowOnly() {
        String outsiderPhone = uniquePhone();
        Long outsider = insertVolunteer(outsiderPhone);   // 真实用户，但没参加
        Fixture f = confirmedFixture();                    // 另一个人参加了

        var result = certificateService.batchUpload(f.activityId, f.slotId,
                List.of(mockPdf(outsiderPhone + ".pdf")), 900L);

        assertEquals(1, result.getFailed());
        assertEquals(0, result.getSucceeded());
        assertTrue(result.getRows().get(0).getError().contains("参加记录"),
                "实际：" + result.getRows().get(0).getError());
        assertTrue(certsOf(outsider).isEmpty(), "绝不能给未参加者留下证书记录");
    }

    /**
     * <b>上传原件不得与懒渲染件互相覆盖。</b>
     *
     * <p>两者原先共用同一个对象 key，后落地的会直接把先落地的顶掉——
     * 协会给的原件可能被系统渲染件覆盖，且没有任何痕迹。</p>
     */
    @Test
    void batchUpload_doesNotOverwriteRenderedObject() {
        ensureGlobalTemplate();
        String phone = uniquePhone();
        Long vid = insertVolunteer(phone);
        Fixture f = confirmedFixtureFor(vid);
        Long certId = certificateService.createForSlot(vid, f.activityId, f.slotId);

        // 先懒渲染出系统件
        certificateService.downloadUrlForVolunteer(certId, vid);
        String renderedKey = certificateMapper.selectById(certId).getFileKey();
        byte[] renderedBytes = InMemoryFileStorageConfig.OBJECTS.get(renderedKey);
        assertNotNull(renderedBytes);

        // 再上传协会原件
        byte[] original = "%PDF-1.7\n协会原件\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        certificateService.batchUpload(f.activityId, f.slotId,
                List.of(new org.springframework.mock.web.MockMultipartFile(
                        "files", phone + ".pdf", "application/pdf", original)), 900L);

        String uploadedKey = certificateMapper.selectById(certId).getFileKey();
        assertNotEquals(renderedKey, uploadedKey, "两者必须是不同的对象 key");
        assertArrayEqualsMsg(renderedBytes, InMemoryFileStorageConfig.OBJECTS.get(renderedKey),
                "渲染件不得被上传覆盖");
        assertArrayEqualsMsg(original, InMemoryFileStorageConfig.OBJECTS.get(uploadedKey),
                "上传的原件必须原样保存");
        assertEquals(CertificateSource.ADMIN_UPLOAD,
                certificateMapper.selectById(certId).getSource(), "以上传件为准");
    }

    /** 后台上传来源的证书若文件缺失，<b>不得</b>用系统样本现渲一张顶上去。 */
    @Test
    void download_adminUploadedCertificateWithoutFile_doesNotSilentlyRender() {
        ensureGlobalTemplate();
        Fixture f = confirmedFixture();
        Long certId = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);
        HonorCertificate patch = new HonorCertificate();
        patch.setId(certId);
        patch.setSource(CertificateSource.ADMIN_UPLOAD);
        certificateMapper.updateById(patch);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> certificateService.downloadUrlForVolunteer(certId, f.volunteerId));
        assertTrue(ex.getMessage().contains("重新上传"), "应提示联系管理员重传，实际：" + ex.getMessage());
    }

    /**
     * <b>上传写入的那一刻证书被并发软删</b>——0 行写入不得报成「上传成功」。
     *
     * <p>这个交错<b>顺序调用造不出来</b>：先删再传的话 {@code createForSlot} 会按
     * 「恢复原记录」口径把它复活，用例永远是绿的。故从存储写入这个缝里注入软删——
     * 它正好落在 {@code createForSlot}（读）与 {@code updateById}（写）之间。</p>
     */
    @Test
    void batchUpload_whenCertificateSoftDeletedMidUpload_isReportedAsFailure() {
        String phone = uniquePhone();
        Long vid = insertVolunteer(phone);
        Fixture f = confirmedFixtureFor(vid);
        Long certId = certificateService.createForSlot(vid, f.activityId, f.slotId);

        InMemoryFileStorageConfig.BEFORE_UPLOAD_PRIVATE =
                () -> certificateMapper.softDelete(certId, 900L, "上传途中撤销");

        var result = certificateService.batchUpload(f.activityId, f.slotId,
                List.of(mockPdf(phone + ".pdf")), 900L);

        assertEquals(0, result.getSucceeded(), "一行都没写进去，不能算成功");
        assertEquals(1, result.getFailed());
        assertTrue(result.getRows().get(0).getError().contains("已被删除"),
                "失败原因要说清是被删了，实际：" + result.getRows().get(0).getError());
        assertNull(certificateMapper.selectByIdIncludeDeleted(certId).getFileKey(),
                "既然报了失败，库里就不该留下 file_key");
    }

    /**
     * <b>签名失败不得计作一次下载。</b>
     *
     * <p>计数被用作「签发前的最后一道闸」（自带 {@code is_deleted = 0}），必须在签名之前执行；
     * 但对象存储抖一下时用户其实什么也没拿到，那一次不该算。而这个数字是要给协会看的
     * （Row 37 F 列「下载次数」）。</p>
     */
    @Test
    void download_whenSigningFails_downloadCountIsRolledBack() {
        ensureGlobalTemplate();
        Fixture f = confirmedFixture();
        Long certId = certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId);
        certificateService.downloadUrlForVolunteer(certId, f.volunteerId);
        assertEquals(1, certificateMapper.selectById(certId).getDownloadCount(), "前置：正常下载计 1 次");

        InMemoryFileStorageConfig.BEFORE_PRESIGN = () -> {
            throw new IllegalStateException("对象存储抖了一下");
        };
        assertThrows(RuntimeException.class,
                () -> certificateService.downloadUrlForVolunteer(certId, f.volunteerId));

        assertEquals(1, certificateMapper.selectById(certId).getDownloadCount(),
                "签名失败时用户什么也没拿到，下载次数不该涨");
    }

    /**
     * <b>配置成 0 必须让应用起不来</b>，而不是静默关掉唯一的自动兜底。
     *
     * <p>batch-size 配成 0 会让每批查回空列表、循环立刻退出；lookback 配成 0 则窗口为空。
     * 两者都让补偿扫描<b>看起来在跑、实际什么也不补</b>，日志上一切正常。</p>
     */
    @Test
    void certificateProperties_rejectZeroValues_atStartup() {
        HonorProperties.Certificate cfg = new HonorProperties.Certificate();
        assertDoesNotThrow(cfg::validate, "缺省值必须是合法的");

        cfg.setReconcileBatchSize(0);
        assertTrue(assertThrows(IllegalStateException.class, cfg::validate)
                .getMessage().contains("reconcile-batch-size"));

        cfg.setReconcileBatchSize(500);
        cfg.setReconcileLookbackHours(0);
        assertTrue(assertThrows(IllegalStateException.class, cfg::validate)
                .getMessage().contains("reconcile-lookback-hours"));

        cfg.setReconcileLookbackHours(72);
        cfg.setReconcileMaxCreatesPerRun(0);
        assertTrue(assertThrows(IllegalStateException.class, cfg::validate)
                .getMessage().contains("reconcile-max-creates-per-run"));

        // 校验必须挂在【真正的 bean】上才会跑：Certificate 是嵌套普通类，
        // @PostConstruct 挂它身上不触发，那种「看着有校验其实没有」比没有更坏。
        HonorProperties props = new HonorProperties();
        props.getCertificate().setReconcileBatchSize(0);
        assertThrows(IllegalStateException.class, props::validateAll,
                "外层 bean 的 validateAll 必须把嵌套配置一起校验");
    }

    /**
     * <b>撞 {@code uk_slot_cert} 时必须认出赢家，不能误判成撞编号。</b>
     *
     * <p>项目跑在 RR 下，而 {@code createForSlot} 在「事件监听器」与「补偿扫描」两条路径上
     * 都处在事务里：快照在事务的第一次 SELECT 就定死了，赢家是在那之后才提交的。
     * 撞键后若用<b>普通快照读</b>取赢家会读到 null，代码便走进「只可能是 uk_cert_no」那一支，
     * 换 5 个号撞 5 次同一个键、日志指向编号生成（那里毫无问题）、最后计入 {@code failed}——
     * 数据始终是对的（唯一键仍在挡重复），<b>错的是报告与诊断方向</b>：
     * 「补完了 = hasMore=false 且 failed=0」这条判据会反过来误报没补完。</p>
     *
     * <p>顺序必须是「事务内先读建快照 → 另一连接建证书并提交 → 再调 createForSlot」，
     * 中间那句 {@code assertNull} 是<b>用例有效性自检</b>：它不成立就说明没跑在 RR 下，
     * 目标分支根本不会执行，用例会退化成「没测」。</p>
     *
     * <p>批量上传那条路径天然免疫、照不出这个缺陷：{@code batchUpload → attachUploadedFile →
     * createForSlot} 全是自调用，事务注解不生效，autocommit 下每条语句各取快照。</p>
     */
    @Test
    void createForSlot_whenWinnerCommittedAfterSnapshot_recognisesSlotConflictNotCertNoConflict() {
        Fixture f = confirmedFixture();
        var rr = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        rr.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        java.util.concurrent.atomic.AtomicReference<Long> returned = new java.util.concurrent.atomic.AtomicReference<>();

        rr.executeWithoutResult(status -> {
            // ① 建立 RR 快照：此刻这个人这一场还没有证书
            assertTrue(certsOf(f.volunteerId).isEmpty());
            // ② 另一个连接抢先建出来并提交
            commitInOtherThread(() ->
                    certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId));
            // 前提自检：对方已提交，但本事务快照仍看不到
            assertNull(certificateMapper.selectBySlotIncludeDeleted(
                            CertificateType.ACTIVITY, f.volunteerId, f.slotId),
                    "RR 快照应仍看不到对方刚提交的行，否则本用例覆盖不到目标分支");
            // ③ 本事务再建：预查受快照限制看不到 ②，必然走到 INSERT → 撞 uk_slot_cert → 当前读取回赢家
            returned.set(certificateService.createForSlot(f.volunteerId, f.activityId, f.slotId));
        });

        List<HonorCertificate> certs = certsOf(f.volunteerId);
        assertEquals(1, certs.size(), "只应有一张——唯一键始终在挡重复");
        assertEquals(certs.get(0).getId(), returned.get(),
                "必须返回赢家那一行；读不到赢家就会被当成撞编号，换号重试到耗尽后抛出");
    }

    /** 在另一个线程（另一条连接、独立事务）里跑完并提交，制造「本事务快照之后的已提交改动」。 */
    private void commitInOtherThread(Runnable action) {
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            pool.submit(action).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("并发前置写入失败", e);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- ⑤ 人工补发：窗口之外唯一的救济 ----------

    /**
     * <b>回看窗口之外的缺失，必须有办法补。</b>
     *
     * <p>定时补偿只看 72 小时内，「事件丢失 + 停机超过窗口」叠加时那张证书永久缺失、
     * 无人发现。本接口是那个洞的救济。</p>
     *
     * <p>⚠️ 它<b>不是</b>「历史活动补发工具」——协会 2026-08-11 已定历史活动不补发。</p>
     */
    @Test
    void manualReconcile_scopedToActivity_backfillsBeyondLookbackWindow() {
        Fixture f = confirmedFixture();
        ActivityAttendance old = attendanceMapper.selectOne(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getSlotId, f.slotId)
                .eq(ActivityAttendance::getVolunteerId, f.volunteerId));
        ActivityAttendance patch = new ActivityAttendance();
        patch.setId(old.getId());
        patch.setSecretaryTime(LocalDateTime.now().minusDays(300));
        attendanceMapper.updateById(patch);

        // 只断言【本夹具】没被补到：库是所有用例共享的，定时补偿这一轮很可能补了别的用例
        // 留下的近期考勤，断它的总返回数等于把别人的状态写进了本用例的前置条件。
        reconcileJob.reconcile();
        assertTrue(certsOf(f.volunteerId).isEmpty(), "前置：定时补偿够不着这条历史数据");

        var first = reconcileJob.reconcile(null, f.activityId);
        assertEquals(1, first.getCreated(), "人工按活动补发应补出这张");
        assertFalse(first.isHasMore(), "一张就补完了，不该说还有剩余");
        assertEquals(1, certsOf(f.volunteerId).size());
        assertEquals(0, reconcileJob.reconcile(null, f.activityId).getCreated(), "再补一次不得重复发");
    }

    /**
     * <b>单次补发封顶，并如实回报「还没补完」。</b>
     *
     * <p>接口同步执行，而网关 {@code proxy_read_timeout} 是 60 秒：一次几千张会在网关 504、
     * 服务端却还在跑，管理员看到失败又点一次，两轮重叠白烧一遍。
     * 补发幂等，所以「重复点到 hasMore=false」就是正确用法。</p>
     *
     * <p><b>剩下的靠「下次调用从头重扫 + 批量差集跳过已有」补上，不靠续传游标</b>——
     * 游标是方法内的局部量，本就不跨调用保留。这也正是不需要任务 id 的原因：
     * 每次调用都是一次完整的、幂等的「把缺的补到上限为止」。
     * 下面第二轮补出第 3 张，验的就是这条：分批不能漏人。</p>
     */
    @Test
    void manualReconcile_capsOneRun_andReportsHasMore() {
        Long aid = insertActivity();
        Long slot = insertSlot(aid, "封顶测试岗");
        List<Long> volunteers = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Long vid = insertVolunteer(null);
            insertConfirmedAttendance(aid, slot, vid);
            volunteers.add(vid);
        }

        var cfg = honorProperties.getCertificate();
        int originalCap = cfg.getReconcileMaxCreatesPerRun();
        try {
            cfg.setReconcileMaxCreatesPerRun(2);
            var run1 = reconcileJob.reconcile(null, aid);
            assertEquals(2, run1.getCreated(), "单次最多补 2 张");
            assertTrue(run1.isHasMore(), "还剩一张没补，必须如实说");

            var run2 = reconcileJob.reconcile(null, aid);
            assertEquals(1, run2.getCreated(), "第二次把剩下那张补上——分批不能漏人");
            assertFalse(run2.isHasMore());
            assertEquals(0, run2.getFailed(), "没有失败条目");
        } finally {
            // 配置是单例 bean，改了必须还原，否则会漏进同上下文的其他用例
            cfg.setReconcileMaxCreatesPerRun(originalCap);
        }

        assertTrue(volunteers.stream().allMatch(v -> certsOf(v).size() == 1),
                "三个人最终各有且只有一张——封顶只是分批，不能漏人");
    }

    /**
     * <b>不接受「无边界全量补发」。</b>
     *
     * <p>两个范围都不给等于扫全库发证，那正是定时任务被明确禁止做的事——
     * 换个入口调用不改变它的性质。守的是「补发的范围必须是人显式圈定的」，
     * 而不是某个具体口径；协会改口径也不该让这条失效。
     */
    @Test
    void manualReconcile_withoutAnyScope_isRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reconcileJob.reconcile(null, null));
        assertTrue(ex.getMessage().contains("范围"), "实际：" + ex.getMessage());
    }

    /**
     * <b>V31 的两条索引必须真的建出来。</b>
     *
     * <p>它们是本批两条新查询的承重件：补偿扫描按 {@code secretary_time} 划窗口、
     * 差集查询按 {@code slot_id IN (…)}。<b>少了任何一条都不会报错</b>，
     * 只会在数据量长起来之后变慢——而那时几乎不可能追回到这次改动。
     * 这正是 V25 记过的那一课（当时是月榜的时间区间吃不到以 volunteer_id 打头的索引）。</p>
     */
    @Test
    void v31_createsTheIndexesThatNewQueriesDependOn() throws Exception {
        assertTrue(hasIndex("activity_attendance", "idx_secretary_time"),
                "没有它，`secretary_status=1 AND secretary_time>=?` 只能走主键顺序扫、逐行过滤");
        assertTrue(hasIndex("honor_certificate", "idx_slot"),
                "没有它，`type=? AND slot_id IN (…)` 只吃得到 uk_slot_cert 的 type 前缀");
        assertFalse(hasIndex("activity_attendance", "idx_secretary"),
                "已被 idx_secretary_time 前缀包含，V31 应一并删掉，留着只是多一份写放大");
    }

    private boolean hasIndex(String table, String index) throws Exception {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM information_schema.statistics"
                     + " WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    // ---------- 夹具 ----------

    /** 一个「已确认参加」的场景：活动 + 场次 + 志愿者 + 已确认考勤。 */
    private record Fixture(Long activityId, Long slotId, Long volunteerId) {
    }

    private Fixture confirmedFixture() {
        return confirmedFixtureFor(insertVolunteer(null));
    }

    private Fixture confirmedFixtureFor(Long volunteerId) {
        Long aid = insertActivity();
        Long slot = insertSlot(aid, "岗位");
        insertConfirmedAttendance(aid, slot, volunteerId);
        return new Fixture(aid, slot, volunteerId);
    }

    private List<HonorCertificate> certsOf(Long volunteerId) {
        return certificateMapper.selectList(Wrappers.<HonorCertificate>lambdaQuery()
                .eq(HonorCertificate::getVolunteerId, volunteerId));
    }

    private static void assertArrayEqualsMsg(byte[] expected, byte[] actual, String msg) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, msg);
    }

    private String uniquePhone() {
        return "138" + String.format("%08d", SEQ.incrementAndGet() % 100_000_000L);
    }

    private static org.springframework.mock.web.MockMultipartFile mockPdf(String name) {
        return new org.springframework.mock.web.MockMultipartFile(
                "files", name, "application/pdf",
                "%PDF-1.7\nfake\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 全局默认样本，并把一份<b>真实 PDF</b> 放进存储——渲染要把它当底图套印。
     *
     * <p>协会的样本上印着公章，这正是 Row 36「盖章的电子证书」的来源；
     * 用例里用一份最简 PDF 代替，验证的是「套印链路走通、且缺样本文件时拒绝出证」。</p>
     */
    private void ensureGlobalTemplate() {
        String key = "certificate-template/global.pdf";
        InMemoryFileStorageConfig.OBJECTS.put(key, samplePdf());
        // 【必须先清后建，不能「有就复用」】样本是全局共享状态：
        // download_whenTemplateFileMissing_isRejected 会留下一个指向不存在文件的全局样本，
        // 「有就复用」会让后续用例挑中那条坏样本，失败信息还指向别的用例——
        // 而这种污染是否发生取决于 JUnit 的方法执行顺序，属于最难查的一类偶发。
        clearTemplates();
        CertificateTemplateSaveDTO dto = new CertificateTemplateSaveDTO();
        dto.setName("全局默认样本");
        dto.setFileKey(key);
        templateService.create(dto, 900L);
    }

    private void clearTemplates() {
        templateService.list().forEach(t -> templateService.delete(t.getId()));
    }

    /** 造一份最简单的合法 PDF，充当协会电子样本。 */
    private static byte[] samplePdf() {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
             var pdf = new com.itextpdf.kernel.pdf.PdfDocument(new com.itextpdf.kernel.pdf.PdfWriter(out))) {
            pdf.addNewPage();
            pdf.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("造样本 PDF 失败", e);
        }
    }

    private Long insertActivity() {
        Activity a = new Activity();
        a.setTitle("证书测试活动_" + System.nanoTime());
        a.setStartTime(LocalDateTime.now().minusHours(3));
        a.setEndTime(LocalDateTime.now().minusHours(1));
        a.setStatus(1);
        a.setRunStatus(2);
        a.setNeedAudit(0);
        a.setMinProjects(0);
        a.setRequireMinJoinCount(0);
        a.setPointsBase(100);
        a.setLeaderMultiplier(new BigDecimal("1.4"));
        a.setManagerMultiplier(new BigDecimal("1.2"));
        activityMapper.insert(a);
        a.setSerialNo(a.getId());
        activityMapper.updateById(a);
        return a.getId();
    }

    private Long insertSlot(Long activityId, String name) {
        ActivitySlot s = new ActivitySlot();
        s.setActivityId(activityId);
        s.setProjectName(name + "_" + System.nanoTime());
        s.setStartTime(LocalDateTime.now().minusHours(3));
        s.setEndTime(LocalDateTime.now().minusHours(1));
        s.setNeedCount(10);
        slotMapper.insert(s);
        return s.getId();
    }

    /** 已签退、<b>待</b>秘书部确认的考勤——{@code secretaryConfirm} 的前置条件。 */
    private Long insertPendingAttendance(Long activityId, Long slotId, Long volunteerId) {
        return insertAttendance(activityId, slotId, volunteerId, 0);
    }

    /** 直接落一条<b>已确认</b>考勤，绕过 secretaryConfirm——等价于「事件丢失」的现场。 */
    private Long insertConfirmedAttendance(Long activityId, Long slotId, Long volunteerId) {
        return insertAttendance(activityId, slotId, volunteerId, 1);
    }

    private Long insertAttendance(Long activityId, Long slotId, Long volunteerId, int secretaryStatus) {
        ActivityAttendance att = new ActivityAttendance();
        att.setActivityId(activityId);
        att.setSlotId(slotId);
        att.setVolunteerId(volunteerId);
        att.setCheckInTime(LocalDateTime.now().minusHours(3));
        att.setCheckOutTime(LocalDateTime.now().minusHours(1));
        att.setServiceMinutes(120);
        att.setAttendStatus(1);
        att.setSecretaryStatus(secretaryStatus);
        if (secretaryStatus == 1) {
            // 补偿扫描按 secretary_time 划回看窗口；不设它这一行永远落在窗口外，
            // 相关用例会以「本来就不该补」假通过——而真实的 secretaryConfirm/补录都会写这一列。
            att.setSecretaryTime(LocalDateTime.now());
        }
        att.setPointsStatus(0);
        att.setPointsFactor(0);
        attendanceMapper.insert(att);
        return att.getId();
    }

    private Long insertVolunteer(String phone) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_" + System.nanoTime() + SEQ.incrementAndGet());
        v.setRealName("证书志愿者");
        v.setStatus(0);
        v.setRegisterTime(LocalDateTime.now());
        if (phone != null) {
            v.setPhone(phone);
            // phone 列是密文，检索靠 phone_hash。不显式设它，findIdsByPhones 永远匹配不上，
            // 批量上传用例就会以「查无此人」通过——测不到真正要测的东西。
            v.setPhoneHash(cryptoUtil.hashPhone(phone));
        }
        volunteerMapper.insert(v);
        return v.getId();
    }
}
