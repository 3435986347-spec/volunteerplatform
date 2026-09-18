package com.hengde.organization.exam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.AdminQueryService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerDisplayView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.organization.exam.dao.OrgExamAttemptMapper;
import com.hengde.organization.exam.dao.OrgExamPaperMapper;
import com.hengde.organization.exam.dao.OrgTempLeaderQualificationMapper;
import com.hengde.organization.exam.entity.OrgExamAttempt;
import com.hengde.organization.exam.entity.OrgExamPaper;
import com.hengde.organization.exam.entity.OrgTempLeaderQualification;
import com.hengde.organization.exam.support.ExamCodes;
import com.hengde.organization.exam.vo.ExamVOs;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 活动临时负责人资格（V4 临时负责人考试批，管理端 {@code org:temp-leader}）：授予（考试及格时，由答卷服务在事务内调）/ 撤销 / 名单 / 导出。
 *
 * <p><b>一个人至多一条没撤销的资格</b>（{@code uk_active_qualification}）。有效期到了不改任何状态位（有效与否按时间现算），
 * 所以那一行会一直占着唯一键——<b>授予新资格前先把这个人已到期的行以「资格到期」收尾</b>，否则到期的人再考及格也拿不到资格。</p>
 *
 * <p><b>撤销</b>（Row 14「评价过低……审核过后可以取消，也可以直接取消」，Q6：组织部手动撤销、不自动）：条件写在 UPDATE 的 WHERE 里
 * （还没撤销、还没到期），并与交卷 / 阅卷持同一把按人的锁（{@link ExamCodes#LOCK_PREFIX}）——这个人的考试与资格写入全部串行。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class TempLeaderService {

    /** 导出上限：超了报错，不静默截断 */
    static final int EXPORT_MAX = 20000;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private OrgTempLeaderQualificationMapper qualificationMapper;
    private OrgExamAttemptMapper attemptMapper;
    private OrgExamPaperMapper paperMapper;
    private VolunteerQueryService volunteerQueryService;
    private AdminQueryService adminQueryService;
    private RedissonClient redissonClient;

    @Autowired
    public void setQualificationMapper(OrgTempLeaderQualificationMapper qualificationMapper) {
        this.qualificationMapper = qualificationMapper;
    }

    @Autowired
    public void setAttemptMapper(OrgExamAttemptMapper attemptMapper) {
        this.attemptMapper = attemptMapper;
    }

    @Autowired
    public void setPaperMapper(OrgExamPaperMapper paperMapper) {
        this.paperMapper = paperMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setAdminQueryService(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 授予资格。<b>必须在调用方事务内、持着这个人的锁调用</b>（答卷服务交卷自动出分 / 阅卷出分时）。
     *
     * @return 新资格 id；这个人已经有一条有效资格时返回 null（不应发生：交卷时已挡住，这里只兜底不让出分失败）
     */
    Long grantInTx(Long volunteerId, Long attemptId, Integer months, LocalDateTime now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("授予临时负责人资格必须在调用方事务内");
        }
        // 先普通读找出到期还占着唯一键的行，再按主键收尾（不按人范围 UPDATE，理由见 closeExpiredById）
        for (OrgTempLeaderQualification old : qualificationMapper.selectList(Wrappers.<OrgTempLeaderQualification>lambdaQuery()
                .select(OrgTempLeaderQualification::getId)
                .eq(OrgTempLeaderQualification::getVolunteerId, volunteerId)
                .isNull(OrgTempLeaderQualification::getRevokedTime)
                .isNotNull(OrgTempLeaderQualification::getExpireTime)
                .le(OrgTempLeaderQualification::getExpireTime, now))) {
            qualificationMapper.closeExpiredById(old.getId(), now, ExamCodes.EXPIRED_REASON);
        }
        OrgTempLeaderQualification q = new OrgTempLeaderQualification();
        q.setVolunteerId(volunteerId);
        q.setSourceType(ExamCodes.SOURCE_EXAM);
        q.setAttemptId(attemptId);
        q.setGrantedTime(now);
        q.setExpireTime(months == null ? null : now.plusMonths(months));
        q.setCreateTime(now);
        try {
            qualificationMapper.insert(q);
        } catch (DuplicateKeyException e) {
            log.warn("[TEMP-LEADER] 授予时已有有效资格，跳过 volunteerId={} attemptId={}", volunteerId, attemptId);
            return null;
        }
        return q.getId();
    }

    /** 撤销一条有效资格。 */
    public void revoke(Long qualificationId, String reason, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("请填写撤销原因");
        }
        String r = reason.trim();
        if (r.length() > 255) {
            throw new BusinessException("撤销原因不超过 255 字");
        }
        OrgTempLeaderQualification q = qualificationId == null ? null : qualificationMapper.selectById(qualificationId);
        if (q == null) {
            throw new BusinessException("资格记录不存在");
        }
        DistributedLockSupport.runLocked(redissonClient, ExamCodes.LOCK_PREFIX + q.getVolunteerId(), () -> {
            int rows = qualificationMapper.revoke(qualificationId, adminId, r, LocalDateTime.now());
            if (rows != 1) {
                throw new BusinessException("这条资格已经撤销或已到期，请刷新");
            }
            return null;
        });
    }

    /**
     * 名单。
     *
     * @param status 1有效 / 2已到期 / 3已撤销；空＝全部
     */
    public PageResult<ExamVOs.Qualification> list(PageQuery query, Integer status, String keyword) {
        LambdaQueryWrapper<OrgTempLeaderQualification> w = filter(status, keyword);
        if (w == null) {
            return PageResult.of(List.of(), 0, query.getPage(), query.getSize());
        }
        IPage<OrgTempLeaderQualification> page = qualificationMapper.selectPage(query.toPage(), w);
        List<ExamVOs.Qualification> rows = toVOs(page.getRecords());
        return PageResult.of(rows, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 批量导出（Row 14 F「信息批量导出」）的表头与行。 */
    public List<List<String>> exportRows(Integer status, String keyword) {
        LambdaQueryWrapper<OrgTempLeaderQualification> w = filter(status, keyword);
        List<OrgTempLeaderQualification> all = w == null ? List.of() : qualificationMapper.selectList(w.last("LIMIT " + (EXPORT_MAX + 1)));
        if (all.size() > EXPORT_MAX) {
            throw new BusinessException("一次最多导出 " + EXPORT_MAX + " 条，请加筛选条件");
        }
        List<List<String>> out = new ArrayList<>();
        for (ExamVOs.Qualification q : toVOs(all)) {
            List<String> row = new ArrayList<>();
            row.add(nz(q.getVolunteerName()));
            row.add(nz(q.getPhone()));
            row.add(q.getStatusLabel());
            row.add(fmt(q.getGrantedTime()));
            row.add(q.getExpireTime() == null ? "长期有效" : fmt(q.getExpireTime()));
            row.add(nz(q.getPaperTitle()));
            row.add(q.getAttemptScore() == null ? "" : String.valueOf(q.getAttemptScore()));
            row.add(nz(q.getRevokedByName()));
            row.add(fmt(q.getRevokedTime()));
            row.add(nz(q.getRevokeReason()));
            out.add(row);
        }
        return out;
    }

    public static List<String> exportHead() {
        return List.of("姓名", "手机号", "状态", "获得时间", "到期时间", "来源试卷", "考试得分", "撤销人", "撤销 / 收尾时间", "撤销原因");
    }

    /** 这个人的全部资格记录（新的在前）。 */
    public List<ExamVOs.Qualification> historyOf(Long volunteerId) {
        return toVOs(qualificationMapper.selectList(Wrappers.<OrgTempLeaderQualification>lambdaQuery()
                .eq(OrgTempLeaderQualification::getVolunteerId, volunteerId)
                .orderByDesc(OrgTempLeaderQualification::getId)));
    }

    /** 现算状态：撤销人非空＝撤销；否则到期时间已过＝到期（含已收尾的）；否则有效。 */
    public static int statusOf(OrgTempLeaderQualification q, LocalDateTime now) {
        if (q.getRevokedBy() != null) {
            return ExamCodes.QUALIFICATION_REVOKED;
        }
        if (q.getExpireTime() != null && !q.getExpireTime().isAfter(now)) {
            return ExamCodes.QUALIFICATION_EXPIRED;
        }
        return q.getRevokedTime() == null ? ExamCodes.QUALIFICATION_ACTIVE : ExamCodes.QUALIFICATION_EXPIRED;
    }

    // ================= 内部 =================

    /** @return null＝关键词查不到人，直接给空结果 */
    private LambdaQueryWrapper<OrgTempLeaderQualification> filter(Integer status, String keyword) {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<OrgTempLeaderQualification> w = Wrappers.lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            List<Long> ids = volunteerQueryService.findIdsByNameOrPhone(keyword.trim(), 500);
            if (ids.isEmpty()) {
                return null;
            }
            w.in(OrgTempLeaderQualification::getVolunteerId, ids);
        }
        if (status != null) {
            switch (status) {
                case ExamCodes.QUALIFICATION_ACTIVE -> w.isNull(OrgTempLeaderQualification::getRevokedTime)
                        .and(x -> x.isNull(OrgTempLeaderQualification::getExpireTime)
                                .or().gt(OrgTempLeaderQualification::getExpireTime, now));
                case ExamCodes.QUALIFICATION_EXPIRED -> w.isNull(OrgTempLeaderQualification::getRevokedBy)
                        .isNotNull(OrgTempLeaderQualification::getExpireTime)
                        .le(OrgTempLeaderQualification::getExpireTime, now);
                case ExamCodes.QUALIFICATION_REVOKED -> w.isNotNull(OrgTempLeaderQualification::getRevokedBy);
                default -> throw new BusinessException("状态只能是 1有效 / 2已到期 / 3已撤销");
            }
        }
        return w.orderByDesc(OrgTempLeaderQualification::getId);
    }

    private List<ExamVOs.Qualification> toVOs(List<OrgTempLeaderQualification> rows) {
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        LocalDateTime now = LocalDateTime.now();
        List<Long> volunteerIds = rows.stream().map(OrgTempLeaderQualification::getVolunteerId).distinct().toList();
        Map<Long, VolunteerDisplayView> people = volunteerQueryService.listDisplayByIds(volunteerIds);
        List<Long> adminIds = rows.stream().map(OrgTempLeaderQualification::getRevokedBy).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> admins = adminIds.isEmpty() ? Map.of() : adminQueryService.listNamesByIds(adminIds);
        List<Long> attemptIds = rows.stream().map(OrgTempLeaderQualification::getAttemptId).filter(Objects::nonNull).distinct().toList();
        Map<Long, OrgExamAttempt> attempts = new HashMap<>();
        if (!attemptIds.isEmpty()) {
            for (OrgExamAttempt a : attemptMapper.selectBatchIds(attemptIds)) {
                attempts.put(a.getId(), a);
            }
        }
        List<Long> paperIds = attempts.values().stream().map(OrgExamAttempt::getPaperId).distinct().toList();
        Map<Long, String> papers = new HashMap<>();
        if (!paperIds.isEmpty()) {
            for (OrgExamPaper p : paperMapper.selectBatchIds(paperIds)) {
                papers.put(p.getId(), p.getTitle());
            }
        }
        List<ExamVOs.Qualification> out = new ArrayList<>(rows.size());
        for (OrgTempLeaderQualification q : rows) {
            ExamVOs.Qualification vo = new ExamVOs.Qualification();
            vo.setId(q.getId());
            vo.setVolunteerId(q.getVolunteerId());
            VolunteerDisplayView v = people.get(q.getVolunteerId());
            vo.setVolunteerName(v == null ? null : v.realName());
            vo.setPhone(v == null ? null : v.phone());
            vo.setAttemptId(q.getAttemptId());
            OrgExamAttempt a = q.getAttemptId() == null ? null : attempts.get(q.getAttemptId());
            if (a != null) {
                vo.setAttemptScore(a.getTotalScore());
                vo.setPaperTitle(papers.get(a.getPaperId()));
            }
            vo.setGrantedTime(q.getGrantedTime());
            vo.setExpireTime(q.getExpireTime());
            int s = statusOf(q, now);
            vo.setStatus(s);
            vo.setStatusLabel(ExamCodes.qualificationStatusLabel(s));
            vo.setRevokedByName(q.getRevokedBy() == null ? null : admins.get(q.getRevokedBy()));
            vo.setRevokedTime(q.getRevokedTime());
            vo.setRevokeReason(q.getRevokeReason());
            out.add(vo);
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "" : t.format(FMT);
    }
}
