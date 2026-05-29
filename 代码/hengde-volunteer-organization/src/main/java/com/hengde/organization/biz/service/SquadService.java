package com.hengde.organization.biz.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerDisplayView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.search.SearchItemVO;
import com.hengde.organization.biz.dao.SquadApplicationMapper;
import com.hengde.organization.biz.dao.VolunteerSquadMapper;
import com.hengde.organization.biz.dto.SquadApplyDTO;
import com.hengde.organization.biz.dto.SquadDTO;
import com.hengde.organization.biz.entity.SquadApplication;
import com.hengde.organization.biz.entity.VolunteerSquad;
import com.hengde.organization.biz.vo.SquadApplicationVO;
import com.hengde.organization.biz.vo.SquadMemberVO;
import com.hengde.organization.biz.vo.SquadVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SquadService {

    private static final int SQUAD_ENABLED = 1;
    private static final int APPLY_PENDING = 0;
    private static final int APPLY_APPROVED = 1;
    private static final int APPLY_REJECTED = 2;

    /** visibleFields 为空时的默认可见字段：仅姓名 */
    private static final Set<String> DEFAULT_VISIBLE_FIELDS = Set.of("realName");

    private VolunteerSquadMapper squadMapper;
    private SquadApplicationMapper applicationMapper;
    private VolunteerMapper volunteerMapper;
    private VolunteerQueryService volunteerQueryService;

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

    public PageResult<SquadVO> list(PageQuery query, boolean admin) {
        IPage<VolunteerSquad> page = squadMapper.selectPage(query.toPage(), Wrappers.<VolunteerSquad>lambdaQuery()
                .eq(!admin, VolunteerSquad::getStatus, SQUAD_ENABLED)
                .orderByDesc(VolunteerSquad::getId));
        return PageResult.of(page.convert(this::toVO));
    }

    /**
     * 志愿者端分队详情：归属差异视图。
     *
     * <p>未归属本分队：仅返回分队及负责人信息，不下发成员名单（belonged=false）。
     * 已归属本分队：额外返回同分队成员名单，成员字段按 {@code visible_fields} 控制，手机号经 auth 解密。</p>
     */
    public SquadVO detail(Long id) {
        VolunteerSquad squad = requireEnabledSquad(id);
        SquadVO vo = toVO(squad);
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
        copy(dto, squad);
        squadMapper.updateById(squad);
    }

    public void delete(Long id) {
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
        return PageResult.of(page.convert(this::toApplicationVO));
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

    @Transactional(rollbackFor = Exception.class)
    public void approveApplication(Long applicationId) {
        SquadApplication application = requireApplication(applicationId);
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
        // 人数上限预校验（memberLimit>0 才限制；0 表示不限）。
        // 注：单事务内预校验，未加分布式锁——V1 后台审批低并发，两条申请并发通过导致轻微超员的概率极低且可接受。
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
    }

    public void rejectApplication(Long applicationId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        // CAS 条件更新：仅 status=待审核 时置为已拒绝，原子化避免并发覆盖
        int rows = applicationMapper.update(null, Wrappers.<SquadApplication>lambdaUpdate()
                .set(SquadApplication::getStatus, APPLY_REJECTED)
                .set(SquadApplication::getRejectReason, reason)
                .set(SquadApplication::getAuditTime, now)
                .set(SquadApplication::getUpdateTime, now)
                .eq(SquadApplication::getId, applicationId)
                .eq(SquadApplication::getStatus, APPLY_PENDING));
        if (rows != 1) {
            throwApplicationConflict(applicationId);
        }
    }

    /** CAS 未命中时给出更友好的报错：不存在 vs 不在待审核状态（真正的状态判定靠上面的原子更新）。 */
    private void throwApplicationConflict(Long applicationId) {
        if (applicationMapper.selectById(applicationId) == null) {
            throw new BusinessException("申请不存在");
        }
        throw new BusinessException("申请不在待审核状态");
    }

    private SquadVO toVO(VolunteerSquad squad) {
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
        vo.setMemberCount(volunteerMapper.selectCount(Wrappers.<Volunteer>lambdaQuery()
                .eq(Volunteer::getSquadId, squad.getId())));
        return vo;
    }

    private SquadApplicationVO toApplicationVO(SquadApplication application) {
        SquadApplicationVO vo = new SquadApplicationVO();
        vo.setId(application.getId());
        vo.setSquadId(application.getSquadId());
        vo.setVolunteerId(application.getVolunteerId());
        Volunteer volunteer = volunteerMapper.selectById(application.getVolunteerId());
        vo.setVolunteerName(volunteer == null ? null : volunteer.getRealName());
        vo.setReason(application.getReason());
        vo.setStatus(application.getStatus());
        vo.setRejectReason(application.getRejectReason());
        vo.setApplyTime(application.getApplyTime());
        return vo;
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
