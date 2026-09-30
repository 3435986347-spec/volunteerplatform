package com.hengde.organization.biz.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.service.SmsNotifyService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerDisplayView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.search.SearchItemVO;
import com.hengde.common.sms.SmsNotifyTemplate;
import com.hengde.organization.biz.constant.SquadApplyStatus;
import com.hengde.organization.biz.dao.SquadApplicationMapper;
import com.hengde.organization.biz.dao.VolunteerSquadMapper;
import com.hengde.organization.biz.dto.SquadApplyDTO;
import com.hengde.organization.biz.dto.SquadDTO;
import com.hengde.organization.biz.entity.SquadApplication;
import com.hengde.organization.biz.entity.VolunteerSquad;
import com.hengde.organization.biz.vo.SquadApplicationVO;
import com.hengde.organization.biz.vo.SquadMemberVO;
import com.hengde.organization.biz.vo.SquadVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SquadService {

    private static final int SQUAD_ENABLED = 1;
    private static final int APPLY_PENDING = SquadApplyStatus.PENDING;
    private static final int APPLY_APPROVED = SquadApplyStatus.APPROVED;
    private static final int APPLY_REJECTED = SquadApplyStatus.REJECTED;

    /** 审核人类型：后台账号（部门审核）。 */
    public static final int AUDITOR_ADMIN = 1;
    /**
     * 审核人类型：分队负责人（志愿者）。协会 2026-09-02：分队加入申请「分队自己审，也可以由部门审核通过」；
     * 需求表 Row 60 原文「由上述分队进行审核是否通过」。
     */
    public static final int AUDITOR_SQUAD_LEADER = 2;

    /** visibleFields 为空时的默认可见字段：仅姓名 */
    private static final Set<String> DEFAULT_VISIBLE_FIELDS = Set.of("realName");

    private VolunteerSquadMapper squadMapper;
    private SquadApplicationMapper applicationMapper;
    private VolunteerMapper volunteerMapper;
    private VolunteerQueryService volunteerQueryService;
    private SmsNotifyService smsNotifyService;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setSquadMapper(VolunteerSquadMapper squadMapper) {
        this.squadMapper = squadMapper;
    }

    @Autowired
    public void setApplicationMapper(SquadApplicationMapper applicationMapper) {
        this.applicationMapper = applicationMapper;
    }

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Autowired
    public void setSmsNotifyService(SmsNotifyService smsNotifyService) {
        this.smsNotifyService = smsNotifyService;
    }

    public PageResult<SquadVO> list(PageQuery query, boolean admin) {
        IPage<VolunteerSquad> page = squadMapper.selectPage(query.toPage(), Wrappers.<VolunteerSquad>lambdaQuery()
                .eq(!admin, VolunteerSquad::getStatus, SQUAD_ENABLED)
                .orderByDesc(VolunteerSquad::getId));
        List<SquadVO> records = toVOList(page.getRecords());
        if (admin) {
            fillLeaderVolunteerNames(records);
        }
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 后台列表补上「负责人志愿者」的姓名。{@code leaderName} 是展示给志愿者看的联系人文字、可以随便写，
     * 与真正拿到审核权的那个志愿者（{@code leaderId}）不一定是同一个人——后台要看得出是谁。
     * 只在后台列表填：志愿者端的分队卡片展示的仍是联系人文字。
     */
    private void fillLeaderVolunteerNames(List<SquadVO> records) {
        Set<Long> ids = new HashSet<>();
        for (SquadVO vo : records) {
            if (vo.getLeaderId() != null) {
                ids.add(vo.getLeaderId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> names = volunteerQueryService.listNamesByIds(ids);
        for (SquadVO vo : records) {
            if (vo.getLeaderId() != null) {
                vo.setLeaderVolunteerName(names.get(vo.getLeaderId()));
            }
        }
    }

    /**
     * 志愿者端分队详情：归属差异视图。
     *
     * <p>未归属本分队：仅返回分队及负责人信息，不下发成员名单（belonged=false）。
     * 已归属本分队：额外返回同分队成员名单，成员字段按 {@code visible_fields} 控制，手机号经 auth 解密。</p>
     */
    public SquadVO detail(Long id) {
        VolunteerSquad squad = requireEnabledSquad(id);
        Long memberCount = volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery()
                .eq(Volunteer::getSquadId, id));
        SquadVO vo = toVO(squad, memberCount == null ? 0L : memberCount);
        Long currentVolunteerId = StpUtil.getLoginIdAsLong();
        Volunteer me = volunteerMapper.selectById(currentVolunteerId);
        boolean belonged = me != null && Objects.equals(me.getSquadId(), id);
        vo.setBelonged(belonged);
        if (belonged) {
            vo.setMembers(loadMembers(squad));
        }
        return vo;
    }

    /** 加载同分队成员（squad_id 命中），字段按 visibleFields 收敛；手机号复用 auth 解密出参。 */
    private List<SquadMemberVO> loadMembers(VolunteerSquad squad) {
        List<Volunteer> members = volunteerMapper.selectList(Wrappers.<Volunteer>lambdaQuery()
                .eq(Volunteer::getSquadId, squad.getId()));
        if (members.isEmpty()) {
            return List.of();
        }
        Set<String> visible = parseVisibleFields(squad.getVisibleFields());
        List<Long> ids = members.stream().map(Volunteer::getId).toList();
        Map<Long, VolunteerDisplayView> displayById = volunteerQueryService.listDisplayByIds(ids);

        List<SquadMemberVO> result = new ArrayList<>(ids.size());
        for (Long vid : ids) {
            VolunteerDisplayView d = displayById.get(vid);
            if (d == null) {
                continue;
            }
            SquadMemberVO m = new SquadMemberVO();
            m.setVolunteerId(vid);
            if (visible.contains("realName")) {
                m.setRealName(d.realName());
            }
            if (visible.contains("school")) {
                m.setSchool(d.school());
            }
            if (visible.contains("grade")) {
                m.setGrade(d.grade());
            }
            if (visible.contains("gender")) {
                m.setGender(d.gender());
            }
            if (visible.contains("phone")) {
                m.setPhone(d.phone());
            }
            result.add(m);
        }
        return result;
    }

    private Set<String> parseVisibleFields(String visibleFields) {
        if (!StringUtils.hasText(visibleFields)) {
            return DEFAULT_VISIBLE_FIELDS;
        }
        return Arrays.stream(visibleFields.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public Long create(SquadDTO dto) {
        requireEligibleLeader(dto.getLeaderId());
        VolunteerSquad squad = new VolunteerSquad();
        copy(dto, squad);
        if (squad.getStatus() == null) {
            squad.setStatus(SQUAD_ENABLED);
        }
        if (squad.getMemberLimit() == null) {
            squad.setMemberLimit(0);
        }
        squadMapper.insert(squad);
        return squad.getId();
    }

    public void update(Long id, SquadDTO dto) {
        VolunteerSquad squad = requireSquad(id);
        // 只校验「换上来的」负责人：原负责人后来被停用时，改个分队名不该因此失败
        if (dto.getLeaderId() != null && !Objects.equals(dto.getLeaderId(), squad.getLeaderId())) {
            requireEligibleLeader(dto.getLeaderId());
        }
        // 一条语句写完：负责人三列显式 set（传空＝清空——updateById 跳过 null，「取消负责人」会静默无效、
        // 那个志愿者照样能审本分队的申请）；其余列沿用「传空不改」，与此前 updateById 的行为一致
        int rows = squadMapper.update(null, Wrappers.<VolunteerSquad>lambdaUpdate()
                .eq(VolunteerSquad::getId, id)
                .set(dto.getName() != null, VolunteerSquad::getName, dto.getName())
                .set(dto.getType() != null, VolunteerSquad::getType, dto.getType())
                .set(dto.getMemberLimit() != null, VolunteerSquad::getMemberLimit, dto.getMemberLimit())
                .set(dto.getVisibleFields() != null, VolunteerSquad::getVisibleFields, dto.getVisibleFields())
                .set(dto.getStatus() != null, VolunteerSquad::getStatus, dto.getStatus())
                .set(VolunteerSquad::getLeaderId, dto.getLeaderId())
                .set(VolunteerSquad::getLeaderName, dto.getLeaderName())
                .set(VolunteerSquad::getLeaderPhone, dto.getLeaderPhone())
                .set(VolunteerSquad::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("分队不存在");
        }
    }

    /**
     * 分队负责人会拿到「审核本分队加入申请」的权力（协会 2026-09-02），所以只收已实名且账号正常的志愿者。
     * 不要求他已归属本分队：新建分队时负责人往往还没加入。
     */
    private void requireEligibleLeader(Long leaderId) {
        if (leaderId == null) {
            return;
        }
        if (volunteerQueryService.filterActiveRegistered(List.of(leaderId)).isEmpty()) {
            throw new BusinessException("分队负责人须是已实名且账号正常的志愿者");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireSquad(id);
        // 删除前挡住「仍有成员」的分队：deleteById 走 BaseEntity @TableLogic 逻辑删除，删后分队对查询不可见，
        // 但 volunteer.squad_id 仍指向它形成悬挂引用（详情/成员视图断链）。需先转移成员或改为停用(status=0)。
        Long members = volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery()
                .eq(Volunteer::getSquadId, id));
        if (members != null && members > 0) {
            throw new BusinessException("分队仍有成员，无法删除（请先转移成员或停用分队）");
        }
        // 已知遗留：squad_application 仍可能残留对已删分队的引用（待审/历史申请的 squadName 将解析为 null）；
        // 本次控制改动面仅挡成员，不连带清理申请记录。
        squadMapper.deleteById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long apply(Long squadId, SquadApplyDTO dto) {
        Long volunteerId = StpUtil.getLoginIdAsLong();
        Volunteer volunteer = requireVolunteer(volunteerId);
        if (volunteer.getSquadId() != null) {
            throw new BusinessException("已归属分队，不能重复申请");
        }
        requireEnabledSquad(squadId);
        Long pending = applicationMapper.selectCount(Wrappers.<SquadApplication>lambdaQuery()
                .eq(SquadApplication::getVolunteerId, volunteerId)
                .eq(SquadApplication::getStatus, APPLY_PENDING));
        if (pending > 0) {
            throw new BusinessException("已有待审核申请");
        }
        SquadApplication application = new SquadApplication();
        application.setSquadId(squadId);
        application.setVolunteerId(volunteerId);
        application.setReason(dto == null ? null : dto.getReason());
        application.setStatus(APPLY_PENDING);
        application.setApplyTime(LocalDateTime.now());
        applicationMapper.insert(application);
        return application.getId();
    }

    public PageResult<SquadApplicationVO> applications(Long squadId, PageQuery query) {
        IPage<SquadApplication> page = applicationMapper.selectPage(query.toPage(), Wrappers.<SquadApplication>lambdaQuery()
                .eq(SquadApplication::getSquadId, squadId)
                .orderByDesc(SquadApplication::getId));
        return PageResult.of(toApplicationVOList(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 全局分队加入申请列表（不按分队 ID），供后台概览「待审分队加入」待办卡片与统一审批入口。
     *
     * <p>无对应的全局接口曾导致前端无法做该待办卡片（须先列分队再逐个进）。{@code status} 为空时默认
     * 仅返回待审（status=0）；传入则按指定状态筛选。每行带 {@code squadName}/{@code volunteerName} 便于识别。</p>
     */
    public PageResult<SquadApplicationVO> applications(PageQuery query, Integer status) {
        int effectiveStatus = status == null ? APPLY_PENDING : status;
        IPage<SquadApplication> page = applicationMapper.selectPage(query.toPage(), Wrappers.<SquadApplication>lambdaQuery()
                .eq(SquadApplication::getStatus, effectiveStatus)
                .orderByDesc(SquadApplication::getId));
        return PageResult.of(toApplicationVOList(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 全局搜索：启用分队按名称匹配的命中总数（供 api 聚合层算精确分页 total）。 */
    public long countSearch(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return 0;
        }
        Long c = squadMapper.selectCount(Wrappers.<VolunteerSquad>lambdaQuery()
                .eq(VolunteerSquad::getStatus, SQUAD_ENABLED)
                .like(VolunteerSquad::getName, keyword));
        return c == null ? 0 : c;
    }

    /** 全局搜索：启用分队按名称匹配，取 [offset, offset+limit) 窗口（供 api 聚合层跨领域分页）。 */
    public List<SearchItemVO> search(String keyword, int offset, int limit) {
        if (!StringUtils.hasText(keyword) || limit <= 0) {
            return List.of();
        }
        List<VolunteerSquad> list = squadMapper.selectList(Wrappers.<VolunteerSquad>lambdaQuery()
                .eq(VolunteerSquad::getStatus, SQUAD_ENABLED)
                .like(VolunteerSquad::getName, keyword)
                .orderByDesc(VolunteerSquad::getId)
                .last("limit " + offset + "," + limit));
        return list.stream()
                .map(s -> new SearchItemVO("squad", s.getId(), s.getName(), s.getType(), null))
                .toList();
    }

    /** 后台（部门）批准加入。 */
    public void approveApplication(Long applicationId, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        approveSerialized(applicationId, AUDITOR_ADMIN, adminId, null);
    }

    /** 后台（部门）拒绝加入。 */
    public void rejectApplication(Long applicationId, String reason, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        doReject(applicationId, reason, AUDITOR_ADMIN, adminId);
    }

    // ---------- 分队负责人在小程序里审（协会 2026-09-02：分队自己审，也可以由部门审核） ----------

    /** 我负责的分队（只列启用中的）。 */
    public List<SquadVO> ledSquads(Long volunteerId) {
        requireVolunteer(volunteerId);
        return toVOList(squadMapper.selectList(Wrappers.<VolunteerSquad>lambdaQuery()
                .eq(VolunteerSquad::getLeaderId, volunteerId)
                .eq(VolunteerSquad::getStatus, SQUAD_ENABLED)
                .orderByAsc(VolunteerSquad::getId)));
    }

    /** 我负责的分队的加入申请；status 为空时只看待审。 */
    public PageResult<SquadApplicationVO> applicationsForLeader(Long volunteerId, Long squadId, Integer status,
                                                                PageQuery query) {
        requireLeaderOf(squadId, volunteerId);
        IPage<SquadApplication> page = applicationMapper.selectPage(query.toPage(), Wrappers.<SquadApplication>lambdaQuery()
                .eq(SquadApplication::getSquadId, squadId)
                .eq(SquadApplication::getStatus, status == null ? APPLY_PENDING : status)
                .orderByDesc(SquadApplication::getId));
        return PageResult.of(toApplicationVOList(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 分队负责人批准加入：只能审自己负责的、启用中的分队，不能审自己的申请。 */
    public void approveByLeader(Long volunteerId, Long applicationId) {
        requireApplicationForLeader(applicationId, volunteerId);
        approveSerialized(applicationId, AUDITOR_SQUAD_LEADER, volunteerId, volunteerId);
    }

    /**
     * 批准的两条路（部门、分队负责人）共用：<b>事务第一条语句锁住分队行</b>，同一分队的批准排成一队。
     *
     * <p>人数上限是「先数一数、再写归属」，两个人同时批同一分队的两份申请，各数各的都没到上限就会一起写进去。
     * 锁放在事务第一条（而不是先读申请再锁）：REPEATABLE READ 下读视图在第一次普通读时定死，
     * 先读了别的，拿到锁之后也看不见前一个批准刚提交的归属。</p>
     *
     * @param leaderVolunteerId 分队负责人的志愿者 id；部门审核传 null
     */
    private void approveSerialized(Long applicationId, int auditorType, Long auditorId, Long leaderVolunteerId) {
        // 事务外只为知道锁哪个分队（申请的分队不会变）
        Long squadId = requireApplication(applicationId).getSquadId();
        transactionTemplate.executeWithoutResult(s -> {
            squadMapper.selectByIdForUpdate(squadId);
            SquadApplication application = leaderVolunteerId == null
                    ? requireApplication(applicationId)
                    : requireApplicationForLeader(applicationId, leaderVolunteerId);
            doApprove(application, auditorType, auditorId);
        });
    }

    /** 分队负责人拒绝加入。 */
    public void rejectByLeader(Long volunteerId, Long applicationId, String reason) {
        requireApplicationForLeader(applicationId, volunteerId);
        doReject(applicationId, reason, AUDITOR_SQUAD_LEADER, volunteerId);
    }

    private SquadApplication requireApplicationForLeader(Long applicationId, Long volunteerId) {
        SquadApplication application = requireApplication(applicationId);
        requireLeaderOf(application.getSquadId(), volunteerId);
        if (Objects.equals(application.getVolunteerId(), volunteerId)) {
            throw new BusinessException("不能审核自己的申请");
        }
        return application;
    }

    /**
     * 当前志愿者必须是这个分队的负责人，分队必须启用、账号必须正常。
     * 不是负责人与分队不存在报同一句话，免得拿这个接口探别的分队。
     */
    private VolunteerSquad requireLeaderOf(Long squadId, Long volunteerId) {
        requireVolunteer(volunteerId);
        VolunteerSquad squad = squadId == null ? null : squadMapper.selectById(squadId);
        if (squad == null || !Integer.valueOf(SQUAD_ENABLED).equals(squad.getStatus())
                || !Objects.equals(squad.getLeaderId(), volunteerId)) {
            throw new BusinessException("只有分队负责人可以审核本分队的加入申请");
        }
        return squad;
    }

    private void doApprove(SquadApplication application, int auditorType, Long auditorId) {
        Long applicationId = application.getId();
        // 语义前置校验：非待审核（如已通过/已拒绝）直接给出准确文案，避免被后续「已归属」等校验抢先误报；
        // 后面的 CAS 更新仍作为并发兜底，二者不冲突。
        if (!Objects.equals(application.getStatus(), APPLY_PENDING)) {
            throw new BusinessException("申请不在待审核状态");
        }
        Long squadId = application.getSquadId();
        // 停用分队不能再批准加入（申请提交后被后台停用时），只能拒绝或重新启用后再批
        VolunteerSquad squad = requireEnabledSquad(squadId);
        Volunteer volunteer = requireVolunteer(application.getVolunteerId());
        if (volunteer.getSquadId() != null) {
            throw new BusinessException("志愿者已归属分队");
        }
        // 人数上限（memberLimit>0 才限制；0 表示不限）。调用方已在事务第一条锁住分队行（approveSerialized），
        // 同一分队的批准在这里是串行的，这一次数到的已经包含前一个批准提交的归属。
        Integer limit = squad.getMemberLimit();
        if (limit != null && limit > 0) {
            Long current = volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery()
                    .eq(Volunteer::getSquadId, squadId));
            if (current != null && current >= limit) {
                throw new BusinessException("分队人数已达上限");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        // CAS 条件更新：仅 status=待审核 时置为已通过，防两个管理员并发审核互相覆盖
        int auditRows = applicationMapper.update(null, Wrappers.<SquadApplication>lambdaUpdate()
                .set(SquadApplication::getStatus, APPLY_APPROVED)
                .set(SquadApplication::getAuditTime, now)
                .set(SquadApplication::getAuditBy, auditorId)
                .set(SquadApplication::getAuditByType, auditorType)
                .set(SquadApplication::getUpdateTime, now)
                .eq(SquadApplication::getId, applicationId)
                .eq(SquadApplication::getStatus, APPLY_PENDING));
        if (auditRows != 1) {
            throwApplicationConflict(applicationId);
        }

        // 条件更新：仅当志愿者仍未归属时赋值，防同一志愿者并发被重复归属
        int rows = volunteerMapper.update(null, Wrappers.<Volunteer>lambdaUpdate()
                .set(Volunteer::getSquadId, squadId)
                .set(Volunteer::getUpdateTime, now)
                .eq(Volunteer::getId, volunteer.getId())
                .isNull(Volunteer::getSquadId));
        if (rows != 1) {
            throw new BusinessException("志愿者已归属分队");
        }
        notifyApplicationResult(application.getVolunteerId(), squad.getName(), "通过", "");
    }

    /**
     * 分队加入申请的审核结果短信（{@code org-join-result}）。
     *
     * <p>申请人提交后就在等这个结果，而系统里没有别的地方会告诉他——
     * 小程序里要自己翻回申请页才看得到。</p>
     *
     * <p>通过与拒绝共用一条模板（{@code ${status}} 区分），驳回原因进 {@code ${remark}}；
     * 没写原因时留空，模板正文末尾本就是一个独立的 {@code ${remark}}，空着不影响句子完整。</p>
     *
     * <p>失败只记日志：审核结论已经落库，不能因为短信没发出去而回滚。</p>
     */
    private void notifyApplicationResult(Long volunteerId, String squadName, String status, String remark) {
        try {
            smsNotifyService.notifyVolunteer(volunteerId, SmsNotifyTemplate.ORG_JOIN_RESULT,
                    SmsNotifyTemplate.ORG_JOIN_RESULT.params(squadName, status, remark));
        } catch (Exception ex) {
            log.error("[SMS-NOTIFY] 分队申请结果通知失败 volunteerId={} squad={}", volunteerId, squadName, ex);
        }
    }

    private void doReject(Long applicationId, String reason, int auditorType, Long auditorId) {
        if (reason != null && reason.length() > 255) {
            throw new BusinessException("拒绝原因不超过 255 字");
        }
        LocalDateTime now = LocalDateTime.now();
        // CAS 条件更新：仅 status=待审核 时置为已拒绝，原子化避免并发覆盖
        int rows = applicationMapper.update(null, Wrappers.<SquadApplication>lambdaUpdate()
                .set(SquadApplication::getStatus, APPLY_REJECTED)
                .set(SquadApplication::getRejectReason, reason)
                .set(SquadApplication::getAuditTime, now)
                .set(SquadApplication::getAuditBy, auditorId)
                .set(SquadApplication::getAuditByType, auditorType)
                .set(SquadApplication::getUpdateTime, now)
                .eq(SquadApplication::getId, applicationId)
                .eq(SquadApplication::getStatus, APPLY_PENDING));
        if (rows != 1) {
            throwApplicationConflict(applicationId);
        }
        // CAS 命中之后再读，只为拿申请人与分队名去发通知；读不到就不发，不影响已落库的结论
        SquadApplication application = applicationMapper.selectById(applicationId);
        if (application != null) {
            VolunteerSquad squad = squadMapper.selectById(application.getSquadId());
            notifyApplicationResult(application.getVolunteerId(),
                    squad == null ? "分队" : squad.getName(), "未通过", reason == null ? "" : reason);
        }
    }

    /** CAS 未命中时给出更友好的报错：不存在 vs 不在待审核状态（真正的状态判定靠上面的原子更新）。 */
    private void throwApplicationConflict(Long applicationId) {
        if (applicationMapper.selectById(applicationId) == null) {
            throw new BusinessException("申请不存在");
        }
        throw new BusinessException("申请不在待审核状态");
    }

    /** 批量组装分队 VO：一次按 squad_id 聚合成员数，避免逐行 N+1。空列表早返回。 */
    private List<SquadVO> toVOList(List<VolunteerSquad> squads) {
        if (squads == null || squads.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> countBySquad = countMembersBySquad(
                squads.stream().map(VolunteerSquad::getId).toList());
        return squads.stream()
                .map(s -> toVO(s, countBySquad.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    private SquadVO toVO(VolunteerSquad squad, long memberCount) {
        SquadVO vo = new SquadVO();
        vo.setId(squad.getId());
        vo.setName(squad.getName());
        vo.setType(squad.getType());
        vo.setLeaderId(squad.getLeaderId());
        vo.setLeaderName(squad.getLeaderName());
        vo.setLeaderPhone(squad.getLeaderPhone());
        vo.setMemberLimit(squad.getMemberLimit());
        vo.setVisibleFields(squad.getVisibleFields());
        vo.setStatus(squad.getStatus());
        vo.setMemberCount(memberCount);
        return vo;
    }

    /** 一次按 squad_id 聚合分队成员数（selectMaps 仍自动带 is_deleted=0）。空 ids 早返回避免 in()。 */
    private Map<Long, Long> countMembersBySquad(List<Long> squadIds) {
        if (squadIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = volunteerMapper.selectMaps(Wrappers.<Volunteer>query()
                .select("squad_id AS sid", "COUNT(*) AS cnt")
                .in("squad_id", squadIds)
                .groupBy("squad_id"));
        Map<Long, Long> result = new HashMap<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.put(numOf(row, "sid"), numOf(row, "cnt"));
        }
        return result;
    }

    /** 批量组装申请 VO：一次取分队名 + 一次取志愿者姓名，避免逐行 N+1。空列表早返回。 */
    private List<SquadApplicationVO> toApplicationVOList(List<SquadApplication> apps) {
        if (apps == null || apps.isEmpty()) {
            return List.of();
        }
        List<Long> squadIds = apps.stream().map(SquadApplication::getSquadId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> squadNameById = squadIds.isEmpty() ? Map.of()
                : squadMapper.selectBatchIds(squadIds).stream()
                .collect(Collectors.toMap(VolunteerSquad::getId, VolunteerSquad::getName));
        List<Long> volunteerIds = apps.stream().map(SquadApplication::getVolunteerId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> volunteerNameById = volunteerQueryService.listNamesByIds(volunteerIds);
        return apps.stream().map(a -> {
            SquadApplicationVO vo = new SquadApplicationVO();
            vo.setId(a.getId());
            vo.setSquadId(a.getSquadId());
            vo.setSquadName(a.getSquadId() == null ? null : squadNameById.get(a.getSquadId()));
            vo.setVolunteerId(a.getVolunteerId());
            vo.setVolunteerName(a.getVolunteerId() == null ? null : volunteerNameById.get(a.getVolunteerId()));
            vo.setReason(a.getReason());
            vo.setStatus(a.getStatus());
            vo.setRejectReason(a.getRejectReason());
            vo.setApplyTime(a.getApplyTime());
            vo.setAuditTime(a.getAuditTime());
            vo.setAuditByType(a.getAuditByType());
            return vo;
        }).toList();
    }

    /** 从 selectMaps 行取数值（兼容驱动对别名大小写处理差异）。 */
    private static long numOf(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) {
            v = row.get(key.toUpperCase());
        }
        return v == null ? 0L : ((Number) v).longValue();
    }

    private VolunteerSquad requireSquad(Long id) {
        VolunteerSquad squad = squadMapper.selectById(id);
        if (squad == null) {
            throw new BusinessException("分队不存在");
        }
        return squad;
    }

    /** 志愿者端入口用：停用(status!=1)的分队对志愿者等同不存在，禁止按 id 直达查看/申请。 */
    private VolunteerSquad requireEnabledSquad(Long id) {
        VolunteerSquad squad = requireSquad(id);
        if (!Integer.valueOf(SQUAD_ENABLED).equals(squad.getStatus())) {
            throw new BusinessException("分队不存在");
        }
        return squad;
    }

    private SquadApplication requireApplication(Long id) {
        SquadApplication application = applicationMapper.selectById(id);
        if (application == null) {
            throw new BusinessException("申请不存在");
        }
        return application;
    }

    private Volunteer requireVolunteer(Long id) {
        Volunteer volunteer = volunteerMapper.selectById(id);
        if (volunteer == null || !Integer.valueOf(0).equals(volunteer.getStatus())) {
            throw new BusinessException("志愿者账号不可用");
        }
        return volunteer;
    }

    private void copy(SquadDTO dto, VolunteerSquad squad) {
        squad.setName(dto.getName());
        squad.setType(dto.getType());
        squad.setLeaderId(dto.getLeaderId());
        squad.setLeaderName(dto.getLeaderName());
        squad.setLeaderPhone(dto.getLeaderPhone());
        squad.setMemberLimit(dto.getMemberLimit());
        squad.setVisibleFields(dto.getVisibleFields());
        squad.setStatus(dto.getStatus());
    }
}
