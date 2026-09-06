package com.hengde.honor.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.activity.service.ActivityCertificateQueryService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.oss.FileStorageService;
import com.hengde.common.oss.FileValidator;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.honor.config.HonorProperties;
import com.hengde.honor.constant.CertificateSource;
import com.hengde.honor.constant.CertificateType;
import com.hengde.honor.dao.HonorCertificateMapper;
import com.hengde.honor.dao.HonorCertificateTemplateMapper;
import com.hengde.honor.entity.HonorCertificate;
import com.hengde.honor.entity.HonorCertificateTemplate;
import com.hengde.honor.vo.CertificateBatchUploadVO;
import com.hengde.honor.vo.CertificateVO;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 电子证书核心（第 4 批）。
 *
 * <p><b>需求原文</b>（xlsx Row 36 C）：「参加完活动后，<b>自动生成一个盖章的电子证书</b>……
 * 电子证书预览和下载」。F 列：「批量上传pdf证书，亦可删除指定某人证书、设置某个活动的电子样本」。</p>
 *
 * <p><b>「自动生成」按「权益自动创建 + PDF 懒渲染」实现</b>：秘书部确认考勤那一刻就写入
 * {@code honor_certificate} 行，志愿者当即在「我的证书」看到条目（与原文一致）；
 * PDF 到首次预览/下载才渲染。用户视角是「参加完就有」，系统不为从不下载的人白跑渲染。</p>
 *
 * @author hengde
 */
@Service
public class CertificateService {

    private static final Logger log = LoggerFactory.getLogger(CertificateService.class);

    private static final DateTimeFormatter CERT_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String RENDER_LOCK_PREFIX = "honor:cert:render:";

    /** 文件名里的手机号：11 位、以 1 开头，且左右不能再挨着数字（否则会从身份证号里截出一段） */
    private static final Pattern PHONE_IN_NAME = Pattern.compile("(?<!\\d)1\\d{10}(?!\\d)");

    /** 单个证书 PDF 上限 10MB */
    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    /** 证书编号撞 {@code uk_cert_no} 时的换号重试次数（碰撞是瞬时的，几次足够） */
    private static final int CERT_NO_MAX_ATTEMPTS = 5;

    private HonorCertificateMapper certificateMapper;
    private HonorCertificateTemplateMapper templateMapper;
    private ActivityCertificateQueryService activityQueryService;
    private VolunteerQueryService volunteerQueryService;
    private FileStorageService fileStorageService;
    private CertificatePdfRenderer pdfRenderer;
    private HonorProperties honorProperties;
    private RedissonClient redissonClient;

    @Autowired
    public void setCertificateMapper(HonorCertificateMapper certificateMapper) {
        this.certificateMapper = certificateMapper;
    }

    @Autowired
    public void setTemplateMapper(HonorCertificateTemplateMapper templateMapper) {
        this.templateMapper = templateMapper;
    }

    @Autowired
    public void setActivityQueryService(ActivityCertificateQueryService activityQueryService) {
        this.activityQueryService = activityQueryService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Autowired
    public void setPdfRenderer(CertificatePdfRenderer pdfRenderer) {
        this.pdfRenderer = pdfRenderer;
    }

    @Autowired
    public void setHonorProperties(HonorProperties honorProperties) {
        this.honorProperties = honorProperties;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    // ---------- ① 自动创建证书权益记录 ----------

    /**
     * 建一张活动证书（幂等）。
     *
     * <p><b>软删后重跑取「恢复原记录」口径</b>：唯一键命中且该行已软删 → 清除软删标记、
     * <b>保留原编号与文件</b>。理由与勋章「驳回不占键」相反——勋章驳回是审核结论、理应可以重来；
     * 证书软删是运营纠错，重跑时把原件恢复比另发一张新编号的更符合「这是同一张证书」的事实。</p>
     *
     * <p><b>为什么先查后插仍然安全</b>：并发下两个线程可能都查不到而同时插入，
     * 此时 {@code uk_slot_cert} 会让其中一个抛 {@link DuplicateKeyException}，
     * 捕获后改为读取赢家那一行——<b>不是靠先查挡住，是靠唯一键挡住</b>。</p>
     *
     * @return 证书 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createForSlot(Long volunteerId, Long activityId, Long slotId) {
        if (volunteerId == null || activityId == null || slotId == null) {
            throw new BusinessException("创建证书需要志愿者、活动与场次");
        }
        // 发证资格必须在这里判，而不是只在某个入口判：
        // 批量上传按文件名手机号匹配，一个错号就会给【从没参加过这场活动的人】发一张证书，
        // 而那个人还能下载到本不属于他的内容。所有创建路径都过这道闸。
        // 判据用活动域的统一口径「秘书部已确认」——时长榜、积分发放都以它为界，证书不另立标准。
        if (!activityQueryService.hasConfirmedAttendance(activityId, slotId, volunteerId)) {
            throw new BusinessException("该志愿者在此活动时间段没有已确认的参加记录，不能发证");
        }
        HonorCertificate exist = certificateMapper.selectBySlotIncludeDeleted(
                CertificateType.ACTIVITY, volunteerId, slotId);
        if (exist != null) {
            restoreIfDeleted(exist);
            return exist.getId();
        }
        for (int attempt = 0; ; attempt++) {
            // 每次重试都新建实体：插入失败的那个可能已被 MP 回填过 id
            HonorCertificate cert = new HonorCertificate();
            cert.setCertNo(nextCertNo());
            cert.setVolunteerId(volunteerId);
            cert.setType(CertificateType.ACTIVITY);
            cert.setActivityId(activityId);
            cert.setSlotId(slotId);
            cert.setSource(CertificateSource.SYSTEM);
            cert.setDownloadCount(0);
            try {
                certificateMapper.insert(cert);
                return cert.getId();
            } catch (DuplicateKeyException e) {
                // 并发下输给了别人：读赢家那一行，并按同一口径处理软删。
                // 【必须是当前读】项目跑在 RR 下，而本方法在「事件监听器」与「补偿扫描」两条路径上
                // 都处在事务里，快照在事务第一次 SELECT 就定死了、看不见赢家后来提交的那一行——
                // 普通读会让下面的 winner == null 成立，把 uk_slot_cert 冲突误判成 uk_cert_no 冲突，
                // 于是换 5 个号撞 5 次同一个键、日志指向编号生成、最后计入 failed，
                // 让「补完了 = hasMore=false 且 failed=0」这条判据反过来误报没补完。
                // 用 FOR SHARE 而非 FOR UPDATE：报重复键时 InnoDB 已给冲突行加了 S 锁，
                // 多个 loser 再抢 X 会互等成死锁（积分账本 PointRecordLockOrderTest 已把这条钉死）。
                // 【两者不是「风险相当、选轻的那个」，是量级差别】FOR UPDATE 下【每个】loser 必然
                // 把 S 升级为 X，两个 loser 就成环；FOR SHARE 则不产生升级——S 本就持有。
                // ⚠️ 唯一可能的升级来自紧接着的 restoreIfDeleted（UPDATE 仍要在这行上取 X，
                //    「它是当前读」并不能免掉这一点）。它在这条分支上【实际不可达】：
                //    赢家是刚插入的新行、is_deleted = 0，restoreIfDeleted 直接空转；
                //    而真正的软删旧行会被上面第一次预查命中并 return，根本走不到 catch。
                //    要碰到它得同时满足「赢家在我快照之后才提交」且「它在我再读之前又被软删」。
                //    ——【这条不可达性是上面那个结论的前提】：谁要把 restoreIfDeleted 挪个位置、
                //    或让本分支去改这一行，就会把每个 loser 都变成升级，请连带重看锁的选择。
                HonorCertificate winner = certificateMapper.selectBySlotForShare(
                        CertificateType.ACTIVITY, volunteerId, slotId);
                if (winner != null) {
                    restoreIfDeleted(winner);
                    return winner.getId();
                }
                // 撞的不是 uk_slot_cert，只可能是 uk_cert_no：**换个号重试**。
                //
                // 【为什么必须重试，而不是像原先那样原样抛出】编号是「秒级时间戳 + 6 位随机」，
                // 同一秒内只有 10^6 个取值。单次交互路径上一秒发不出几张，撞号确实是「异常」，
                // 抛出去让用户重试一次即可——`nextCertNo` 的注释说的就是那个语境。
                // 但**批量补发把这个前提翻了过来**：一次上千张会把大量证书挤进同一秒，
                // 生日碰撞概率 ≈ 1 − exp(−k²/2·10^6)（k = 该秒内张数），
                // 千张量级下一次跑里撞上一次属于常态而非意外。
                // 而补偿扫描是逐条 catch + log 的，抛出去只会被吞成一行日志：
                // 那个人静默无证，接口却回 hasMore=false 报「补完了」——
                // 与本批一直在修的「报告成功、实际没做」是同一形状。
                // 碰撞是瞬时的、换个号就好，重试无副作用也不影响幂等（uk_slot_cert 仍在挡重复发放）。
                if (attempt >= CERT_NO_MAX_ATTEMPTS - 1) {
                    throw e;
                }
                log.warn("证书编号碰撞，换号重试 attempt={} volunteerId={} slotId={}",
                        attempt + 1, volunteerId, slotId);
            }
        }
    }

    /**
     * 批量查出「这批场次里已经有证书」的组合键 {@code volunteerId:slotId}，<b>含已软删</b>。
     *
     * <p>供补偿扫描做差集：逐行 select 会让每轮变成 N 次查询，表一大就慢得没法按小时跑。</p>
     */
    public Set<String> existingKeys(Collection<Long> slotIds) {
        if (slotIds == null || slotIds.isEmpty()) {
            return Set.of();
        }
        return new java.util.HashSet<>(
                certificateMapper.selectExistingKeys(CertificateType.ACTIVITY, slotIds));
    }

    /** 与 {@link #existingKeys} 配套的组合键写法，两处必须一致。 */
    public static String subjectKey(Long volunteerId, Long slotId) {
        return volunteerId + ":" + slotId;
    }

    /**
     * 缺则建、有则不动——供补偿扫描使用。
     *
     * <p>与 {@link #createForSlot} 的差别只在<b>不复活软删的行</b>：
     * 软删是管理员的明确意思表示，补偿任务不该把它悄悄撤销。
     * 「重跑复活原件」那条口径只适用于<b>业务重新触发</b>（重复确认/补录），不适用于定时扫描。</p>
     *
     * @return true=本次新建了一张
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean createIfAbsent(Long volunteerId, Long activityId, Long slotId) {
        // 【这次查询与 createForSlot 里那次重复，是刻意保留的】两次都不能删：
        // 这一次决定「软删的行算已存在、不复活」，是本方法与 createForSlot 的唯一差别所在；
        // 那一次是 createForSlot 对【所有】调用方的承诺（重跑复活原件、保留原编号）。
        // 代价是 uk_slot_cert 上多一次点查，且只发生在【确实要新建】的行上
        // （补偿扫描的批量差集已经把常见情况滤掉了）。
        // 为省这一次点查去拆分创建路径，换来的是「两条创建路径口径微妙不同」——
        // 那正是这批里反复出问题的那类缺陷。
        HonorCertificate exist = certificateMapper.selectBySlotIncludeDeleted(
                CertificateType.ACTIVITY, volunteerId, slotId);
        if (exist != null) {
            return false;
        }
        createForSlot(volunteerId, activityId, slotId);
        return true;
    }

    private void restoreIfDeleted(HonorCertificate cert) {
        if (Integer.valueOf(1).equals(cert.getIsDeleted())) {
            certificateMapper.restore(cert.getId());
            log.info("证书重跑命中已软删记录，按「恢复原记录」口径复活 certId={} certNo={}",
                    cert.getId(), cert.getCertNo());
        }
    }

    /**
     * 生成证书编号。
     *
     * <p>形态参考原型 P83 卡片「证书编号：564641541665156」——一串数字。
     * 取「时间到秒 + 6 位随机」共 20 位。</p>
     *
     * <p><b>别把碰撞概率当成「可忽略」</b>（这里原先就是这么写的，是错的）：同一秒内只有
     * 10^6 个取值，批量补发一次上千张会把大量证书挤进同一秒，
     * 碰撞概率 ≈ 1 − exp(−k²/2·10^6)（k = 该秒内张数），千张量级下一次跑撞上一次是常态。
     * 真撞上会被 {@code uk_cert_no} 拦成异常而<b>不是静默产生重号</b>——这一点仍然成立，
     * 但<b>拦下来之后必须换号重试</b>（见 {@link #createForSlot}），
     * 否则在逐条 catch 的批量路径上就变成「那个人静默无证、接口却报补完了」。</p>
     */
    private String nextCertNo() {
        return LocalDateTime.now().format(CERT_NO_TIME)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    // ---------- ② 我的证书列表 ----------

    /** 我的证书（软删的不返回，由 {@code @TableLogic} 自动过滤）。 */
    public PageResult<CertificateVO> myCertificates(Long volunteerId, PageQuery query) {
        // 分页而不是一次全量：证书随参加的活动逐场累积，老志愿者手上会有几十上百张，
        // 小程序那边要做触底加载就必须拿到 total。V43 之前这里回的是裸 List，
        // 改成 PageResult 是【破坏性改动】，已与小程序侧约定同步切换。
        Page<HonorCertificate> page = query.toPage();
        certificateMapper.selectPage(page, Wrappers.<HonorCertificate>lambdaQuery()
                .eq(HonorCertificate::getVolunteerId, volunteerId)
                .orderByDesc(HonorCertificate::getId));
        return PageResult.of(toVos(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 后台证书汇总查询（可按志愿者/活动筛选）。
     *
     * @param includeDeleted 是否连已软删的一起返回。<b>后台必须能看到已删的</b>——
     *                       否则删完就再也找不回那个 id，「撤销删除」入口形同虚设。
     */
    public PageResult<CertificateVO> adminList(PageQuery query, Long volunteerId, Long activityId,
                                               boolean includeDeleted) {
        Page<HonorCertificate> page = query.toPage();
        certificateMapper.selectAdminPage(page, volunteerId, activityId, includeDeleted);
        return PageResult.of(toVos(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    private List<CertificateVO> toVos(List<HonorCertificate> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, ActivityCertificateQueryService.SlotDisplay> slotById =
                activityQueryService.listSlotDisplays(rows.stream().map(HonorCertificate::getSlotId)
                        .filter(java.util.Objects::nonNull).distinct().toList());
        Map<Long, String> nameById = volunteerQueryService.listNamesByIds(
                rows.stream().map(HonorCertificate::getVolunteerId).distinct().toList());

        return rows.stream().map(c -> {
            CertificateVO vo = new CertificateVO();
            vo.setId(c.getId());
            vo.setCertNo(c.getCertNo());
            vo.setType(c.getType());
            vo.setVolunteerId(c.getVolunteerId());
            vo.setVolunteerName(nameById.get(c.getVolunteerId()));
            vo.setActivityId(c.getActivityId());
            vo.setSlotId(c.getSlotId());
            ActivityCertificateQueryService.SlotDisplay s = slotById.get(c.getSlotId());
            if (s != null) {
                vo.setActivityTitle(s.activityTitle());
                vo.setSlotProjectName(s.slotProjectName());
                vo.setSlotStartTime(s.slotStartTime());
                vo.setSlotEndTime(s.slotEndTime());
            }
            vo.setSource(c.getSource());
            vo.setDownloadCount(c.getDownloadCount());
            vo.setFileReady(c.getFileKey() != null);
            vo.setGenerateTime(c.getGenerateTime());
            vo.setCreateTime(c.getCreateTime());
            vo.setDeleted(Integer.valueOf(1).equals(c.getIsDeleted()));
            vo.setDeletedReason(c.getDeletedReason());
            vo.setDeletedTime(c.getDeletedTime());
            return vo;
        }).toList();
    }

    // ---------- ③ 预览/下载：懒渲染 + 短期签名 URL + 计数 ----------

    /**
     * 取下载链接。志愿者端调用，<b>先校验归属</b>。
     *
     * @param certId      证书 id
     * @param volunteerId 当前登录志愿者（取自登录态，不接受入参）
     */
    public String downloadUrlForVolunteer(Long certId, Long volunteerId) {
        HonorCertificate cert = certificateMapper.selectById(certId);
        if (cert == null || !cert.getVolunteerId().equals(volunteerId)) {
            // 不区分「不存在」与「不是你的」——否则可以拿这个接口枚举他人证书是否存在
            throw new BusinessException("证书不存在");
        }
        return signedUrl(cert);
    }

    /** 后台下载：不校验归属，但同样只返回短期签名 URL。 */
    public String downloadUrlForAdmin(Long certId) {
        HonorCertificate cert = certificateMapper.selectById(certId);
        if (cert == null) {
            throw new BusinessException("证书不存在");
        }
        return signedUrl(cert);
    }

    /**
     * 文件缺失时懒渲染并回填，然后签名，最后累加下载次数。
     *
     * <p><b>并发要点</b>：按 certificate_id 上分布式锁，锁内二次读——先到者渲染并回填，
     * 后到者进锁时已能读到 {@code file_key}，直接复用，不会渲染两份、也不会覆盖。
     * 回填用 {@code UPDATE ... WHERE file_key IS NULL} 的 CAS 作最后一道保险
     * （锁失效、跨进程时钟等极端情况）。</p>
     */
    private String signedUrl(HonorCertificate cert) {
        String key = cert.getFileKey();
        if (key == null) {
            key = renderAndFill(cert);
        }
        // 【删除竞态】读出证书到这里之间，管理员可能已经把它软删了（渲染还要几百毫秒，窗口是真实的）。
        // 计数语句自带 is_deleted = 0，故拿它当【最后一道闸】：影响 0 行就说明期间被删了，
        // 此时必须放弃签发——签名 URL 一旦交出去，在有效期内谁拿到都能取，撤不回来。
        // 顺序也必须是「先确认可发放、再签名」，不能先签好再判。
        if (certificateMapper.incrementDownloadCount(cert.getId()) != 1) {
            throw new BusinessException("证书不存在");
        }
        Duration ttl = Duration.ofSeconds(honorProperties.getCertificate().getDownloadUrlTtlSeconds());
        try {
            return fileStorageService.presignGet(key, ttl);
        } catch (RuntimeException e) {
            // 闸门必须在签名【之前】，但签名失败时用户其实什么也没拿到，不该记一次下载。
            // 故补偿性回退这一次计数——否则对象存储抖一下，下载次数就虚高，
            // 而这个数字是要给协会看的（Row 37 F 列「下载次数」）。
            certificateMapper.decrementDownloadCount(cert.getId());
            throw e;
        }
    }

    private String renderAndFill(HonorCertificate cert) {
        // 后台上传的证书文件本就该在库里；没有 file_key 说明上传环节出了问题。
        // 此时【不能】用系统样本现渲一张顶上去——那会把「协会给的那一张」悄悄换成系统生成的，
        // 而志愿者与后台都看不出发生过替换。
        if (!Integer.valueOf(CertificateSource.SYSTEM).equals(cert.getSource())) {
            throw new BusinessException("该证书文件缺失，请联系管理员重新上传");
        }
        RLock lock = redissonClient.getLock(RENDER_LOCK_PREFIX + cert.getId());
        boolean locked = false;
        try {
            locked = lock.tryLock(honorProperties.getCertificate().getRenderLockWaitSeconds(),
                    TimeUnit.SECONDS);
            if (!locked) {
                throw new BusinessException("证书正在生成中，请稍后重试");
            }
            // 锁内二次读：可能在等锁期间已由他人渲染完成
            HonorCertificate fresh = certificateMapper.selectById(cert.getId());
            if (fresh != null && fresh.getFileKey() != null) {
                return fresh.getFileKey();
            }
            byte[] pdf = pdfRenderer.render(buildContent(cert));
            String objectKey = systemObjectKey(cert.getCertNo());
            fileStorageService.uploadPrivate(pdf, objectKey, "application/pdf");
            int rows = certificateMapper.fillFileKeyIfAbsent(cert.getId(), objectKey);
            if (rows == 0) {
                // CAS 落空：他人已回填。用他那一份，别用自己刚传的——两份内容一致，
                // 但库里只认一个 key，返回自己的会指向一个没被记录的对象。
                HonorCertificate winner = certificateMapper.selectById(cert.getId());
                return winner != null && winner.getFileKey() != null ? winner.getFileKey() : objectKey;
            }
            return objectKey;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("证书生成被中断，请重试");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 系统渲染件的对象 key，形如 {@code certificate/system/{certNo}.pdf}。
     *
     * <p><b>与后台上传件分处不同前缀，这不是整理癖</b>：两者原先共用
     * {@code certificate/{certNo}.pdf}，于是「懒渲染正在上传」与「管理员上传原件」会写同一个对象，
     * 后落地的把先落地的<b>直接覆盖</b>——协会给的原件可能被系统渲染件顶掉，且无痕迹。
     * key 分开后两者互不相干，最终以哪一份为准由 {@code file_key} 的写入顺序决定（管理员上传优先）。</p>
     */
    private String systemObjectKey(String certNo) {
        return honorProperties.getCertificate().getObjectDir() + "/system/" + certNo + ".pdf";
    }

    /**
     * 后台上传件的对象 key，带随机后缀。
     *
     * <p>后缀让<b>同一张证书的多次重传各自成对象</b>，不会互相覆盖：
     * 重传往往正是因为上一份传错了，把错的那份留在存储里反而便于追查。</p>
     *
     * <p><b>对象生命周期：本批刻意「只增不删」，这是决定不是遗漏。</b></p>
     * <ul>
     *   <li><b>软删证书时不删对象</b>——软删可撤销（{@code /restore}），
     *       删了文件就撤不回来了，「恢复原记录、保留原编号与文件」这条口径也就落空；</li>
     *   <li><b>重传时不删旧对象</b>——旧的那份正是「传错了」的证据，纠纷时要查；</li>
     *   <li>因此唯一的真正孤儿是<b>被重传取代的旧上传件</b>（以及极少数渲染成功但 CAS 落空的系统件）。
     *       它们不可达但会一直占存储。<b>目前没有回收机制</b>：证书量级下这点占用可忽略，
     *       而写一个「扫描不可达对象并删除」的任务风险远大于收益——删错就是真丢文件。
     *       待存储成本真的成为问题时，应做成<b>先标记、隔离期后再删</b>的两段式，不是直接删。</li>
     * </ul>
     */
    private String uploadObjectKey(String certNo) {
        return honorProperties.getCertificate().getObjectDir() + "/upload/" + certNo
                + "-" + java.util.UUID.randomUUID().toString().substring(0, 8) + ".pdf";
    }

    /**
     * 组装渲染内容：活动名、岗位、岗位时间、服务时长、姓名、编号，<b>以及协会样本底图</b>。
     *
     * <p>活动侧事实一律走 {@link ActivityCertificateQueryService} 这个窄接口取，
     * 不直连活动域的 Mapper。</p>
     */
    private CertificatePdfRenderer.Content buildContent(HonorCertificate cert) {
        HonorCertificateTemplate template = requireTemplate(cert.getActivityId());
        // 样本 PDF 就是「盖章」的来源：公章印在协会给的这份底图上（xlsx Row 36「盖章的电子证书」）。
        // 读不出来就直接失败，不退回自绘无章版式——设计要点 ② 明确禁止那种退化。
        byte[] templatePdf = fileStorageService.download(template.getFileKey());

        ActivityCertificateQueryService.CertificateSubject subject =
                activityQueryService.findSubject(cert.getActivityId(), cert.getSlotId(), cert.getVolunteerId());
        // 活动侧事实缺失（考勤被删/活动被删/数据不一致）时【拒绝出证】，
        // 而不是渲染出一张姓名与活动都空着的「正式证书」——那种东西发出去比不发更麻烦：
        // 它有编号、有公章底图，看着完全正规，却证明不了任何事。
        if (subject == null) {
            throw new BusinessException("该证书对应的参加记录已不存在，无法生成证书");
        }
        Map<Long, String> names = volunteerQueryService.listNamesByIds(List.of(cert.getVolunteerId()));
        String name = names.get(cert.getVolunteerId());
        if (name == null || name.isBlank()) {
            throw new BusinessException("该志愿者尚未实名，无法生成证书");
        }
        return new CertificatePdfRenderer.Content(
                name,
                subject.activityTitle(),
                subject.slotProjectName(),
                subject.slotStartTime(),
                subject.slotEndTime(),
                subject.serviceMinutes(),
                cert.getCertNo(),
                templatePdf);
    }

    /**
     * 未配置任何电子样本时<b>拒绝生成</b>。
     *
     * <p>设计要点 ②：不要退化成出一张无章的证书——那比没有更糟，
     * 志愿者会拿着一张不被承认的 PDF 以为自己有证书了。</p>
     *
     * <p>查找顺序：先找本活动的样本（{@code activity:{id}}），没有再退回全局默认（{@code global}）。
     * 这正是 Row 36 F「设置<b>某个活动</b>的电子样本」所要求的按活动绑定。</p>
     */
    private HonorCertificateTemplate requireTemplate(Long activityId) {
        if (activityId != null) {
            HonorCertificateTemplate byActivity = findTemplate(HonorCertificateTemplate.activityScope(activityId));
            if (byActivity != null) {
                return byActivity;
            }
        }
        HonorCertificateTemplate global = findTemplate(HonorCertificateTemplate.SCOPE_GLOBAL);
        if (global == null) {
            throw new BusinessException("尚未配置证书电子样本，无法生成证书，请先在后台设置");
        }
        return global;
    }

    private HonorCertificateTemplate findTemplate(String scopeKey) {
        return templateMapper.selectOne(Wrappers.<HonorCertificateTemplate>lambdaQuery()
                .eq(HonorCertificateTemplate::getScopeKey, scopeKey)
                .eq(HonorCertificateTemplate::getEnabled, 1)
                .last("limit 1"));
    }

    // ---------- ④ 后台批量上传 PDF 证书 ----------

    /**
     * 批量上传 PDF 证书（Row 36 F 列第 ① 项「批量上传pdf证书」——协会线下已有的证书直接入库）。
     *
     * <p><b>文件 ↔ 志愿者的映射按文件名里的手机号</b>：文件名形如 {@code 13800138000.pdf}
     * 或 {@code 13800138000_张三.pdf}，取<b>第一段连续 11 位数字</b>作手机号。
     * <b>不按姓名匹配——重名必错配</b>。手机号换 id 走 auth 的窄接口
     * {@code VolunteerQueryService#findIdsByPhones}，honor 侧不接触任何 PII 密文。</p>
     *
     * <p><b>逐条成败独立</b>：单个文件匹配不上只记一行失败，不让整批回滚——
     * 一次传上百个文件，因一个手机号写错就全批失败，运营只能反复试错。
     * 故本方法<b>不加 {@code @Transactional}</b>，每条各自成事务。</p>
     *
     * <p>已存在的证书（如秘书部确认时自动建的那条）会被<b>复用并挂上传的文件</b>，
     * 不另建一张；来源随之改为「后台上传」，此后不再参与懒渲染。</p>
     *
     * @param activityId 本批证书所属活动
     * @param slotId     本批证书所属场次（一场活动一个证书，故批次以场次为单位）
     */
    public CertificateBatchUploadVO batchUpload(Long activityId, Long slotId,
                                                List<MultipartFile> files, Long operatorId) {
        if (activityId == null || slotId == null) {
            throw new BusinessException("请指定本批证书所属的活动与场次");
        }
        CertificateBatchUploadVO result = new CertificateBatchUploadVO();
        if (files == null || files.isEmpty()) {
            return result;
        }
        // 先把本批所有手机号一次性换成 id，避免逐个文件查库
        Map<String, Long> idByPhone = volunteerQueryService.findIdsByPhones(
                files.stream().map(f -> extractPhone(f.getOriginalFilename()))
                        .filter(java.util.Objects::nonNull).distinct().toList());

        for (MultipartFile file : files) {
            CertificateBatchUploadVO.Row row = new CertificateBatchUploadVO.Row();
            row.setFileName(file.getOriginalFilename());
            String phone = extractPhone(file.getOriginalFilename());
            row.setPhone(phone);
            try {
                if (phone == null) {
                    throw new BusinessException("文件名里找不到 11 位手机号");
                }
                Long volunteerId = idByPhone.get(phone);
                if (volunteerId == null) {
                    throw new BusinessException("该手机号未匹配到志愿者");
                }
                FileValidator.validate(file, Set.of("pdf"), MAX_UPLOAD_BYTES);
                row.setCertificateId(attachUploadedFile(volunteerId, activityId, slotId, file, operatorId));
                row.setSuccess(true);
                result.setSucceeded(result.getSucceeded() + 1);
            } catch (Exception e) {
                row.setSuccess(false);
                row.setError(e instanceof BusinessException ? e.getMessage() : "处理失败：" + e.getMessage());
                result.setFailed(result.getFailed() + 1);
                log.warn("批量上传证书单条失败 file={} phone={}", row.getFileName(), phone, e);
            }
            result.getRows().add(row);
        }
        result.setTotal(files.size());
        return result;
    }

    /**
     * 单条：找到或建出证书，把上传的 PDF 挂上去。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：它由同类的 {@link #batchUpload} 调用，
     * 自调用本就绕过事务代理、加了也不生效（那种「看着有其实没有」的注解比没有更坏）。
     * 而这里也<b>不需要</b>逐条事务：最坏情况是证书行已建、文件没挂上，
     * 那正是「已创建待渲染」这个正常状态，重传即可修复，不会产生脏数据。</p>
     */
    public Long attachUploadedFile(Long volunteerId, Long activityId, Long slotId,
                                   MultipartFile file, Long operatorId) {
        // createForSlot 内含「必须有已确认的参加记录」这道闸，
        // 故文件名手机号写错时不会给未参加者凭空发一张证书，而是在这里就被拒。
        Long certId = createForSlot(volunteerId, activityId, slotId);
        HonorCertificate cert = certificateMapper.selectById(certId);
        String objectKey = uploadObjectKey(cert.getCertNo());
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new BusinessException("读取上传文件失败：" + e.getMessage());
        }
        fileStorageService.uploadPrivate(bytes, objectKey, "application/pdf");

        HonorCertificate update = new HonorCertificate();
        update.setId(certId);
        update.setFileKey(objectKey);
        // 来源改为「后台上传」：此后不再参与懒渲染，避免系统样本把协会给的原件覆盖掉
        update.setSource(CertificateSource.ADMIN_UPLOAD);
        update.setGenerateTime(LocalDateTime.now());
        // 【必须查影响行数】updateById 经 @TableLogic 会带上 is_deleted = 0：
        // 若在 createForSlot 与这一句之间证书被并发软删，这里会 0 行、什么也没写，
        // 而批量上传却把这一条报成「成功」——运营看到成功、志愿者手里却没有文件。
        if (certificateMapper.updateById(update) != 1) {
            throw new BusinessException("证书已被删除，本次上传未生效，请先恢复该证书");
        }
        return certId;
    }

    /** 取文件名里第一段连续 11 位数字作手机号；找不到返回 null。 */
    private static String extractPhone(String fileName) {
        if (fileName == null) {
            return null;
        }
        Matcher m = PHONE_IN_NAME.matcher(fileName);
        return m.find() ? m.group() : null;
    }

    // ---------- ⑤ 软删与恢复 ----------

    /** 软删指定某人的证书，留痕删除人/时间/原因（Row 36 F 列第 ② 项）。 */
    @Transactional(rollbackFor = Exception.class)
    public void softDelete(Long certId, String reason, Long operatorId) {
        if (certificateMapper.softDelete(certId, operatorId, reason) != 1) {
            throw new BusinessException("证书不存在或已删除");
        }
    }

    /** 撤销软删（与「恢复原记录」口径配套的显式入口）。 */
    @Transactional(rollbackFor = Exception.class)
    public void restore(Long certId) {
        if (certificateMapper.restore(certId) != 1) {
            throw new BusinessException("证书不存在或未被删除");
        }
    }
}
