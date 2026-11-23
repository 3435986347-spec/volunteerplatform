package com.hengde.organization.biz.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.SmsNotifyService;
import com.hengde.auth.service.VolunteerAdminService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerFlagInfoView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.sms.SmsNotifyTemplate;
import com.hengde.organization.biz.dao.ManagerApplicationMapper;
import com.hengde.organization.biz.dto.ManagerApplyDTO;
import com.hengde.organization.biz.entity.ManagerApplication;
import com.hengde.organization.biz.vo.ManagerApplicationVO;
import com.hengde.auth.vo.VolunteerContactView;
import com.hengde.organization.form.service.FormSubmissionService;
import com.hengde.organization.form.support.FormFlow;
import com.hengde.organization.form.vo.FormVOs;
import org.redisson.api.RedissonClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 报名管理团队：志愿者提交申请（问卷/简历）→ 后台审核 → 通过即置 volunteer.manager_flag=1（V23）。
 *
 * <p>复用 auth {@link VolunteerAdminService#setManagerFlag} 标记通道与 org:manager-flag 权限点，
 * 不新增权限点；通过<b>仅置 manager_flag、不自动授任何权限点</b>（具体权限仍由超管在授权页给）。</p>
 *
 * <p>并发：apply 以「志愿者维度」Redisson 锁串行化「查重→insert」防双提交；approve/reject 用 CAS 条件更新。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class ManagerApplicationService {

    private static final int STATUS_PENDING = 0;
    private static final int STATUS_APPROVED = 1;
    private static final int STATUS_REJECTED = 2;
    private static final int MANAGER_FLAG_ON = 1;
    private static final int MAX_REJECT_REASON = 512;
    private static final String LOCK_KEY_PREFIX = "lock:manager-apply:volunteer:";

    private ManagerApplicationMapper applicationMapper;
    private VolunteerQueryService volunteerQueryService;
    private VolunteerAdminService volunteerAdminService;
    private SmsNotifyService smsNotifyService;
    private RedissonClient redissonClient;
    private TransactionTemplate transactionTemplate;
    private FormSubmissionService formSubmissionService;

    @Autowired
    public void setFormSubmissionService(FormSubmissionService formSubmissionService) {
        this.formSubmissionService = formSubmissionService;
    }

    @Autowired
    public void setApplicationMapper(ManagerApplicationMapper applicationMapper) {
        this.applicationMapper = applicationMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setVolunteerAdminService(VolunteerAdminService volunteerAdminService) {
        this.volunteerAdminService = volunteerAdminService;
    }

    @Autowired
    public void setSmsNotifyService(SmsNotifyService smsNotifyService) {
        this.smsNotifyService = smsNotifyService;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 志愿者提交报名管理团队申请；志愿者维度锁防双击/弱网重放插两条待审。返回申请 id。 */
    public Long apply(Long volunteerId, ManagerApplyDTO dto) {
        if (volunteerId == null) {
            throw new BusinessException("未登录");
        }
        return DistributedLockSupport.runLocked(redissonClient, LOCK_KEY_PREFIX + volunteerId,
                () -> transactionTemplate.execute(s -> doApply(volunteerId, dto)));
    }

    private Long doApply(Long volunteerId, ManagerApplyDTO dto) {
        VolunteerFlagInfoView info = requireActiveRegisteredVolunteer(volunteerId);
        if (Integer.valueOf(MANAGER_FLAG_ON).equals(info.managerFlag())) {
            throw new BusinessException("您已是管理团队，无需申请");
        }
        Long pending = applicationMapper.selectCount(Wrappers.<ManagerApplication>lambdaQuery()
                .eq(ManagerApplication::getVolunteerId, volunteerId)
                .eq(ManagerApplication::getStatus, STATUS_PENDING));
        if (pending != null && pending > 0) {
            throw new BusinessException("您已有待审核的申请，请耐心等待");
        }
        // 协会发布了「报名管理团队」问卷时，答卷与申请同一事务落库；没有问卷时返回 null、照旧只落固定三项
        Long formSubmissionId = formSubmissionService.submitForScene(FormFlow.SCENE_MANAGER_APPLICATION, volunteerId,
                dto.getAnswers());
        LocalDateTime now = LocalDateTime.now();
        ManagerApplication app = new ManagerApplication();
        app.setVolunteerId(volunteerId);
        app.setReason(dto.getReason());
        app.setExperience(dto.getExperience());
        app.setExpectDepartment(dto.getExpectDepartment());
        app.setFormSubmissionId(formSubmissionId);
        app.setStatus(STATUS_PENDING);
        app.setApplyTime(now);
        applicationMapper.insert(app);
        return app.getId();
    }

    /** 本人最近一条申请（状态回显），无则返回 null。 */
    public ManagerApplicationVO myApplication(Long volunteerId) {
        if (volunteerId == null) {
            return null;
        }
        List<ManagerApplication> rows = applicationMapper.selectList(Wrappers.<ManagerApplication>lambdaQuery()
                .eq(ManagerApplication::getVolunteerId, volunteerId)
                .orderByDesc(ManagerApplication::getApplyTime)
                .orderByDesc(ManagerApplication::getId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : toVO(rows.get(0), null);
    }

    /** 后台审核列表：status 为空默认待审；带申请人姓名。 */
    public PageResult<ManagerApplicationVO> list(PageQuery query, Integer status) {
        int effectiveStatus = status == null ? STATUS_PENDING : status;
        IPage<ManagerApplication> page = applicationMapper.selectPage(query.toPage(),
                Wrappers.<ManagerApplication>lambdaQuery()
                        .eq(ManagerApplication::getStatus, effectiveStatus)
                        // 按 apply_time desc + id desc：对齐 V23 idx_status_apply_time(status, apply_time)，让该索引覆盖筛选+排序
                        .orderByDesc(ManagerApplication::getApplyTime)
                        .orderByDesc(ManagerApplication::getId));
        List<ManagerApplication> records = page.getRecords();
        Set<Long> ids = records.stream().map(ManagerApplication::getVolunteerId).collect(Collectors.toSet());
        Map<Long, String> nameById = ids.isEmpty() ? Map.of() : volunteerQueryService.listNamesByIds(ids);
        List<ManagerApplicationVO> vos = new ArrayList<>(records.size());
        for (ManagerApplication a : records) {
            vos.add(toVO(a, nameById.get(a.getVolunteerId())));
        }
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 后台详情：申请 + 申请人电话 + 问卷答卷逐题答案。 */
    public ManagerApplicationVO detail(Long id) {
        ManagerApplication app = id == null ? null : applicationMapper.selectById(id);
        if (app == null) {
            throw new BusinessException("申请不存在");
        }
        VolunteerContactView c = volunteerQueryService.listContactsByIds(Set.of(app.getVolunteerId()))
                .get(app.getVolunteerId());
        ManagerApplicationVO vo = toVO(app, c == null ? null : c.realName());
        vo.setVolunteerPhone(c == null ? null : c.phone());
        vo.setFormAnswers(app.getFormSubmissionId() == null ? List.of()
                : formSubmissionService.answerViews(app.getFormSubmissionId()));
        return vo;
    }

    /**
     * 批量下载（Row 44「类似于简历提交 / 批量下载功能」）：固定三项 + 电话 + 问卷答卷（每道题「题目：答案」一行）。
     * 不同时期的申请可能答的是不同的问卷，所以答卷合成一列，而不是按某一份问卷的题目拆列。
     */
    public List<List<String>> exportRows(Integer status) {
        List<ManagerApplication> apps = applicationMapper.selectList(Wrappers.<ManagerApplication>lambdaQuery()
                .eq(status != null, ManagerApplication::getStatus, status)
                .orderByDesc(ManagerApplication::getApplyTime)
                .orderByDesc(ManagerApplication::getId)
                .last("LIMIT " + (EXPORT_LIMIT + 1)));
        if (apps.size() > EXPORT_LIMIT) {
            throw new BusinessException("申请超过 " + EXPORT_LIMIT + " 条，一次导不完，请按状态分批导出");
        }
        Map<Long, VolunteerContactView> contacts = apps.isEmpty() ? Map.of()
                : volunteerQueryService.listContactsByIds(
                        apps.stream().map(ManagerApplication::getVolunteerId).collect(Collectors.toSet()));
        Map<Long, List<FormVOs.AnswerView>> answers = formSubmissionService.answerViews(apps.stream()
                .map(ManagerApplication::getFormSubmissionId).filter(java.util.Objects::nonNull).toList());
        List<List<String>> rows = new ArrayList<>(apps.size());
        for (ManagerApplication a : apps) {
            VolunteerContactView c = contacts.get(a.getVolunteerId());
            List<FormVOs.AnswerView> views = a.getFormSubmissionId() == null ? List.of()
                    : answers.getOrDefault(a.getFormSubmissionId(), List.of());
            rows.add(List.of(
                    String.valueOf(a.getId()),
                    c == null || c.realName() == null ? "" : c.realName(),
                    c == null || c.phone() == null ? "" : c.phone(),
                    nz(a.getReason()), nz(a.getExperience()), nz(a.getExpectDepartment()),
                    statusLabel(a.getStatus()),
                    a.getApplyTime() == null ? "" : a.getApplyTime().toString().replace('T', ' '),
                    a.getAuditTime() == null ? "" : a.getAuditTime().toString().replace('T', ' '),
                    nz(a.getRejectReason()),
                    views.stream().map(v -> v.getSort() + ". " + v.getTitle() + "：" + v.getDisplay())
                            .collect(Collectors.joining("\n"))));
        }
        return rows;
    }

    public static final List<String> EXPORT_HEAD = List.of("申请编号", "姓名", "手机号", "申请理由", "相关经历",
            "期望部门", "状态", "申请时间", "审核时间", "驳回原因", "问卷答卷");
    private static final int EXPORT_LIMIT = 20000;

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String statusLabel(Integer s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case STATUS_PENDING -> "待审核";
            case STATUS_APPROVED -> "已通过";
            case STATUS_REJECTED -> "已驳回";
            default -> "未知";
        };
    }

    /**
     * 审核通过（顺序钉死）：requirePending 读出 → 重校验申请人仍 active+registered →
     * setManagerFlag(1) → 最后 CAS 置通过；CAS affected≠1 抛错 → 整事务回滚（含 manager_flag），
     * 杜绝「申请通过但标记失败」/「重复审核留下错误标记」。仅置 manager_flag、不自动授权限点。
     */
    public void approve(Long id, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        transactionTemplate.executeWithoutResult(s -> doApprove(id, adminId));
    }

    private void doApprove(Long id, Long adminId) {
        ManagerApplication app = applicationMapper.selectById(id);
        if (app == null || !Integer.valueOf(STATUS_PENDING).equals(app.getStatus())) {
            throw new BusinessException("申请不在待审核状态");
        }
        // 提交后被禁用/注销则拒绝、申请保持待审（不转通过）
        requireActiveRegisteredVolunteer(app.getVolunteerId());
        volunteerAdminService.setManagerFlag(app.getVolunteerId(), MANAGER_FLAG_ON, adminId);
        LocalDateTime now = LocalDateTime.now();
        int rows = applicationMapper.update(null, Wrappers.<ManagerApplication>lambdaUpdate()
                .set(ManagerApplication::getStatus, STATUS_APPROVED)
                .set(ManagerApplication::getAuditBy, adminId)
                .set(ManagerApplication::getAuditTime, now)
                .set(ManagerApplication::getUpdateTime, now)
                .eq(ManagerApplication::getId, id)
                .eq(ManagerApplication::getStatus, STATUS_PENDING));
        if (rows != 1) {
            throw new BusinessException("申请不在待审核状态");
        }
        notifyResult(app.getVolunteerId(), "通过", "");
    }

    /**
     * 报名管理团队的审核结果短信（复用 {@code org-join-result}，{@code orgName} 填「管理团队」）。
     *
     * <p>与分队申请共用一条模板：两者都是「申请加入某个组织、由人来批」，
     * 模板正文「您申请加入${orgName}，审核结果：${status}。${remark}」放进「管理团队」同样通顺，
     * 不必为它单独报备一条。</p>
     *
     * <p>失败只记日志——通过时 {@code manager_flag} 已经置好，不能因短信回滚。</p>
     */
    private void notifyResult(Long volunteerId, String status, String remark) {
        try {
            smsNotifyService.notifyVolunteer(volunteerId, SmsNotifyTemplate.ORG_JOIN_RESULT,
                    SmsNotifyTemplate.ORG_JOIN_RESULT.params("管理团队", status, remark));
        } catch (Exception ex) {
            log.error("[SMS-NOTIFY] 管理团队申请结果通知失败 volunteerId={}", volunteerId, ex);
        }
    }

    /** 审核驳回：CAS 待审→驳回，记原因。 */
    public void reject(Long id, String reason, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        if (reason != null && reason.length() > MAX_REJECT_REASON) {
            throw new BusinessException("驳回原因不超过" + MAX_REJECT_REASON + "字");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = applicationMapper.update(null, Wrappers.<ManagerApplication>lambdaUpdate()
                .set(ManagerApplication::getStatus, STATUS_REJECTED)
                .set(ManagerApplication::getRejectReason, reason)
                .set(ManagerApplication::getAuditBy, adminId)
                .set(ManagerApplication::getAuditTime, now)
                .set(ManagerApplication::getUpdateTime, now)
                .eq(ManagerApplication::getId, id)
                .eq(ManagerApplication::getStatus, STATUS_PENDING));
        if (rows != 1) {
            throw new BusinessException("申请不在待审核状态");
        }
        // CAS 命中之后再读，只为拿申请人 id 发通知
        ManagerApplication app = applicationMapper.selectById(id);
        if (app != null) {
            notifyResult(app.getVolunteerId(), "未通过", reason == null ? "" : reason);
        }
    }

    /** apply/approve 共用：校验账号正常且已实名，返回 flag 信息（含 managerFlag）；含 getFlagInfo==null 兜底。 */
    private VolunteerFlagInfoView requireActiveRegisteredVolunteer(Long volunteerId) {
        if (!volunteerQueryService.isActive(volunteerId)) {
            throw new BusinessException("账号状态异常，无法操作");
        }
        VolunteerFlagInfoView info = volunteerQueryService.getFlagInfo(volunteerId);
        if (info == null || !info.registered()) {
            throw new BusinessException("请先完成实名注册再申请");
        }
        return info;
    }

    private ManagerApplicationVO toVO(ManagerApplication a, String volunteerName) {
        ManagerApplicationVO vo = new ManagerApplicationVO();
        vo.setId(a.getId());
        vo.setVolunteerId(a.getVolunteerId());
        vo.setVolunteerName(volunteerName);
        vo.setReason(a.getReason());
        vo.setExperience(a.getExperience());
        vo.setExpectDepartment(a.getExpectDepartment());
        vo.setStatus(a.getStatus());
        vo.setRejectReason(a.getRejectReason());
        vo.setApplyTime(a.getApplyTime());
        vo.setAuditTime(a.getAuditTime());
        vo.setFormSubmissionId(a.getFormSubmissionId());
        return vo;
    }
}
