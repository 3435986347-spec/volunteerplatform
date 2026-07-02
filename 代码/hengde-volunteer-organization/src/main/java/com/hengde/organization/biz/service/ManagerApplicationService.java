package com.hengde.organization.biz.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.VolunteerAdminService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerFlagInfoView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.organization.biz.dao.ManagerApplicationMapper;
import com.hengde.organization.biz.dto.ManagerApplyDTO;
import com.hengde.organization.biz.entity.ManagerApplication;
import com.hengde.organization.biz.vo.ManagerApplicationVO;
import org.redisson.api.RedissonClient;
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
    private RedissonClient redissonClient;
    private TransactionTemplate transactionTemplate;

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
        LocalDateTime now = LocalDateTime.now();
        ManagerApplication app = new ManagerApplication();
        app.setVolunteerId(volunteerId);
        app.setReason(dto.getReason());
        app.setExperience(dto.getExperience());
        app.setExpectDepartment(dto.getExpectDepartment());
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
        return vo;
    }
}
