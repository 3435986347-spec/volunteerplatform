package com.hengde.organization.biz.service;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.hengde.organization.biz.dao.VolunteerGroupLeaderHistoryMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMapper;
import com.hengde.organization.biz.dao.VolunteerGroupMemberMapper;
import com.hengde.organization.biz.dto.GroupCreateDTO;
import com.hengde.organization.biz.dto.GroupImportRow;
import com.hengde.organization.biz.entity.VolunteerGroup;
import com.hengde.organization.biz.entity.VolunteerGroupLeaderHistory;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import com.hengde.organization.biz.vo.GroupLeaderHistoryVO;
import com.hengde.organization.biz.vo.GroupMemberVO;
import com.hengde.organization.biz.vo.GroupVO;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import org.springframework.dao.DuplicateKeyException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class GroupService {

    private static final int GROUP_PENDING = 0;
    private static final int GROUP_ACTIVE = 1;
    private static final int GROUP_REJECTED = 2;
    private static final int GROUP_DISSOLVED = 3;
    private static final int MEMBER_PENDING = 0;
    private static final int MEMBER_ACTIVE = 1;
    private static final int MEMBER_REJECTED = 2;
    private static final int MEMBER_LEFT = 3;
    private static final int MEMBER_REMOVED = 4;
    private static final int ROLE_MEMBER = 0;
    private static final int ROLE_LEADER = 1;
    /** 管理员（V7 起接口启用），承担日常审批/移除，与组长协同；不参与组长转移 */
    private static final int ROLE_ADMIN = 2;
    /** 管理员人数上限（不含组长） */
    private static final int MAX_ADMIN_COUNT = 3;

    /** 组长变更：志愿者主动转移（V1 未开放志愿者端入口，预留） */
    private static final int OP_TYPE_VOLUNTEER_TRANSFER = 1;
    /** 组长变更：后台管理员转移 */
    private static final int OP_TYPE_ADMIN_TRANSFER = 2;
    /** 组长变更：建组审批首次任命 */
    private static final int OP_TYPE_INITIAL = 3;

    /** 「志愿者维度」锁等待秒数（与 EnrollmentService 一致） */
    private static final long LOCK_WAIT_SEC = 5;

    private VolunteerGroupMapper groupMapper;
    private VolunteerGroupMemberMapper memberMapper;
    private VolunteerGroupLeaderHistoryMapper leaderHistoryMapper;
    private VolunteerMapper volunteerMapper;
    private VolunteerQueryService volunteerQueryService;
    private RedissonClient redissonClient;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setGroupMapper(VolunteerGroupMapper groupMapper) {
        this.groupMapper = groupMapper;
    }

    @Autowired
    public void setMemberMapper(VolunteerGroupMemberMapper memberMapper) {
        this.memberMapper = memberMapper;
    }

    @Autowired
    public void setLeaderHistoryMapper(VolunteerGroupLeaderHistoryMapper leaderHistoryMapper) {
        this.leaderHistoryMapper = leaderHistoryMapper;
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
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public PageResult<GroupVO> list(PageQuery query, String keyword, boolean admin) {
        LambdaQueryWrapper<VolunteerGroup> wrapper = Wrappers.lambdaQuery();
        if (!admin) {
            wrapper.eq(VolunteerGroup::getStatus, GROUP_ACTIVE);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(VolunteerGroup::getName, keyword).or().like(VolunteerGroup::getGroupNo, keyword));
        }
        wrapper.orderByDesc(VolunteerGroup::getId);
        IPage<VolunteerGroup> page = groupMapper.selectPage(query.toPage(), wrapper);
        return PageResult.of(toGroupVOList(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public PageResult<GroupVO> applications(PageQuery query) {
        IPage<VolunteerGroup> page = groupMapper.selectPage(query.toPage(), Wrappers.<VolunteerGroup>lambdaQuery()
                .eq(VolunteerGroup::getStatus, GROUP_PENDING)
                .orderByDesc(VolunteerGroup::getId));
        return PageResult.of(toGroupVOList(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 全局搜索：正常状态小组按名称/编号匹配的命中总数（供 api 聚合层算精确分页 total）。 */
    public long countSearch(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return 0;
        }
        Long c = groupMapper.selectCount(Wrappers.<VolunteerGroup>lambdaQuery()
                .eq(VolunteerGroup::getStatus, GROUP_ACTIVE)
                .and(w -> w.like(VolunteerGroup::getName, keyword).or().like(VolunteerGroup::getGroupNo, keyword)));
        return c == null ? 0 : c;
    }

    /** 全局搜索：正常状态小组按名称/编号匹配，取 [offset, offset+limit) 窗口（供 api 聚合层跨领域分页）。 */
    public List<SearchItemVO> search(String keyword, int offset, int limit) {
        if (!StringUtils.hasText(keyword) || limit <= 0) {
            return List.of();
        }
        List<VolunteerGroup> list = groupMapper.selectList(Wrappers.<VolunteerGroup>lambdaQuery()
                .eq(VolunteerGroup::getStatus, GROUP_ACTIVE)
                .and(w -> w.like(VolunteerGroup::getName, keyword).or().like(VolunteerGroup::getGroupNo, keyword))
                .orderByDesc(VolunteerGroup::getId)
                .last("limit " + offset + "," + limit));
        return list.stream()
                .map(g -> new SearchItemVO("group", g.getId(), g.getName(), g.getDescription(), null))
                .toList();
    }

    public GroupVO detail(Long id) {
        VolunteerGroup group = requireGroup(id);
        return toGroupVO(group);
    }

    /**
     * 发起新小组。
     *
     * <p>并发：以「志愿者维度」分布式锁串行化同一发起人的 create/join——单纯靠 ensureNoActiveGroup 的「读后写」
     * 在并发下有 read-then-insert 竞态（两个请求都读到空，最后各插一条 → 同一人多组）。锁外开锁内开事务、
     * 事务提交后才释放锁，避免另一请求拿到锁时读不到未提交的插入。</p>
     */
    public Long create(GroupCreateDTO dto) {
        return createForVolunteer(currentVolunteerId(), dto);
    }

    /**
     * 带显式 volunteerId 的建组入口。
     *
     * <p>用途：(a) 并发测试绕过 Sa-Token ThreadLocal；(b) 未来若加「管理员代发起小组」场景可直接复用。
     * 业务正常调用仍走 {@link #create(GroupCreateDTO)}——后者从 Sa-Token 取 loginId，对外接口不变。</p>
     */
    public Long createForVolunteer(Long volunteerId, GroupCreateDTO dto) {
        return runLocked(volunteerId, () -> transactionTemplate.execute(s -> doCreate(dto, volunteerId)));
    }

    private Long doCreate(GroupCreateDTO dto, Long volunteerId) {
        ensureNormalVolunteer(volunteerId);
        ensureNoActiveGroup(volunteerId);

        VolunteerGroup group = new VolunteerGroup();
        group.setGroupNo("G" + System.currentTimeMillis());
        group.setName(dto.getName());
        group.setDescription(dto.getDescription());
        group.setLeaderId(volunteerId);
        group.setStatus(GROUP_PENDING);
        groupMapper.insert(group);

        // 同步给发起人插一条 PENDING 组长成员行，让待审核期间也命中「一人一组」(ensureNoActiveGroup 按 PENDING/ACTIVE 统计)
        // 避免：建组待审核期间又去申请/加入别的小组，等批准后形成一人多组
        VolunteerGroupMember pendingLeader = new VolunteerGroupMember();
        pendingLeader.setGroupId(group.getId());
        pendingLeader.setVolunteerId(volunteerId);
        pendingLeader.setRole(ROLE_LEADER);
        pendingLeader.setStatus(MEMBER_PENDING);
        pendingLeader.setApplyTime(LocalDateTime.now());
        memberMapper.insert(pendingLeader);

        return group.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public Integer importGroups(MultipartFile file, Long adminId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("导入文件不能为空");
        }
        List<GroupImportRow> rows;
        try {
            rows = EasyExcel.read(file.getInputStream())
                    .head(GroupImportRow.class)
                    .sheet()
                    .doReadSync();
        } catch (IOException e) {
            throw new BusinessException("读取导入文件失败");
        }
        return importGroupRows(rows, adminId);
    }

    /**
     * 批量导入小组的核心处理（行数据已从 Excel 解析完成）。与 {@link #importGroups(MultipartFile, Long)} 拆开，
     * 使校验/落库逻辑可脱离 Excel 解析单独测试；二者共用同一事务语义（整批任一行失败则全回滚）。
     *
     * <p>导入语义＝「线下已成立小组直接入库」，管理员的导入动作视同审批：每个小组写
     * approved_time/approved_by、组长成员行写 audit_by，并补一条 {@link #OP_TYPE_INITIAL} 组长历史，
     * 使其审批轨迹与 {@link #approveCreate} 创建的小组完全一致（finding：导入不能断审计链）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Integer importGroupRows(List<GroupImportRow> rows, Long adminId) {
        // 导入要写 approved_by/audit_by/operator_id——operator 缺失会再次造出审计链不完整的数据，
        // 故在 public 入口先卡死（控制器走 StpAdminUtil 必非空，此处防测试/未来复用误传 null）。
        if (adminId == null) {
            throw new BusinessException("导入操作人不能为空");
        }
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (GroupImportRow row : rows) {
            if (row == null || !StringUtils.hasText(row.getName()) || row.getLeaderId() == null) {
                continue;
            }
            ensureNormalVolunteer(row.getLeaderId());
            String groupNo = StringUtils.hasText(row.getGroupNo())
                    ? row.getGroupNo()
                    : "G" + System.currentTimeMillis() + count;
            Long exists = groupMapper.selectCount(Wrappers.<VolunteerGroup>lambdaQuery()
                    .eq(VolunteerGroup::getGroupNo, groupNo));
            if (exists != null && exists > 0) {
                throw new BusinessException("小组编号已存在：" + groupNo);
            }

            // 批量导入仅用于「把线下已成立的小组录入系统」，一律建为正常(ACTIVE)状态：
            //   - 管理员的导入动作本身即等同审批，无需也不该再走待审核(PENDING)流程；
            //   - 若放行 PENDING，导入时不会插组长成员行（占不住「一人一组」），后续 approveCreate
            //     又找不到待生效组长行而失败——既绕开 V9 DB 兜底，又造出永远无法批准的死小组。
            // 状态列留空默认 ACTIVE；显式填非 ACTIVE 一律拒绝（已拒绝/已解散历史组无 row 字段支撑，亦无导入场景）。
            if (row.getStatus() != null && !Objects.equals(row.getStatus(), GROUP_ACTIVE)) {
                throw new BusinessException("批量导入仅支持正常状态的小组（状态列请留空或填 1）");
            }

            // 与 create()/join() 同义的「一人一组」前置校验；即便并发绕过，下方插组长成员行时
            // V9 DB 唯一约束(active_volunteer_lock)会再兜一道（DuplicateKeyException → 友好提示）。
            ensureNoActiveGroup(row.getLeaderId());

            LocalDateTime now = LocalDateTime.now();
            VolunteerGroup group = new VolunteerGroup();
            group.setGroupNo(groupNo);
            group.setName(row.getName());
            group.setDescription(row.getDescription());
            group.setLeaderId(row.getLeaderId());
            group.setStatus(GROUP_ACTIVE);
            // 导入视同审批：补审批元数据，与 approveCreate 一致，避免审计链断裂
            group.setApprovedTime(now);
            group.setApprovedBy(adminId);
            groupMapper.insert(group);

            VolunteerGroupMember leader = new VolunteerGroupMember();
            leader.setGroupId(group.getId());
            leader.setVolunteerId(row.getLeaderId());
            leader.setRole(ROLE_LEADER);
            leader.setStatus(MEMBER_ACTIVE);
            leader.setApplyTime(now);
            leader.setAuditTime(now);
            leader.setAuditBy(adminId);
            try {
                memberMapper.insert(leader);
            } catch (DuplicateKeyException e) {
                // V9 唯一约束：active_volunteer_lock 冲突 = 志愿者已在其他 active/pending 小组
                throw new BusinessException("导入失败：志愿者 id=" + row.getLeaderId() + " 已在其他小组");
            }

            // 补首条组长历史，作为该组组长变更轨迹起点（与 approveCreate 的 OP_TYPE_INITIAL 一致）
            recordLeaderChange(group.getId(), null, row.getLeaderId(), OP_TYPE_INITIAL, adminId,
                    "批量导入直接入库视同审批首次任命");
            count++;
        }
        return count;
    }

    /**
     * 申请加入小组。锁策略同 {@link #create}：志愿者维度锁 + 事务，串行化「检查-插入」防同人多组。
     */
    public void join(Long groupId) {
        Long volunteerId = currentVolunteerId();
        runLocked(volunteerId, () -> transactionTemplate.execute(s -> {
            doJoin(groupId, volunteerId);
            return null;
        }));
    }

    private void doJoin(Long groupId, Long volunteerId) {
        ensureNormalVolunteer(volunteerId);
        VolunteerGroup group = requireGroup(groupId);
        if (!Objects.equals(group.getStatus(), GROUP_ACTIVE)) {
            throw new BusinessException("小组未开放加入");
        }
        ensureNoActiveGroup(volunteerId);

        VolunteerGroupMember member = new VolunteerGroupMember();
        member.setGroupId(groupId);
        member.setVolunteerId(volunteerId);
        member.setRole(ROLE_MEMBER);
        member.setStatus(MEMBER_PENDING);
        member.setApplyTime(LocalDateTime.now());
        memberMapper.insert(member);
    }

    @Transactional(rollbackFor = Exception.class)
    public void leave(Long groupId) {
        Long volunteerId = currentVolunteerId();
        VolunteerGroupMember member = currentActiveMember(groupId, volunteerId);
        if (Objects.equals(member.getRole(), ROLE_LEADER)) {
            throw new BusinessException("组长需先转移组长后才能退出");
        }
        member.setStatus(MEMBER_LEFT);
        memberMapper.updateById(member);
    }

    public List<GroupMemberVO> members(Long groupId) {
        requireGroup(groupId);
        // 同组内可见：仅本组在册成员可查看成员名单，防止任意志愿者凭 id 窥探他组成员信息
        currentActiveMember(groupId, currentVolunteerId());
        List<VolunteerGroupMember> rows = memberMapper.selectList(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getGroupId, groupId)
                .eq(VolunteerGroupMember::getStatus, MEMBER_ACTIVE)
                .orderByAsc(VolunteerGroupMember::getRole)
                .orderByAsc(VolunteerGroupMember::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        // 同组内展示姓名/学校/电话；手机号经 auth 解密，避免直接读加密字段
        List<Long> ids = rows.stream().map(VolunteerGroupMember::getVolunteerId).distinct().toList();
        Map<Long, VolunteerDisplayView> displayById = volunteerQueryService.listDisplayByIds(ids);
        return rows.stream().map(m -> toMemberVO(m, displayById)).toList();
    }

    /**
     * 待审核加入申请列表。仅本组组长/管理员可见（与 approveMember/rejectMember 同一鉴权口径）。
     *
     * <p>缺口背景：批准/拒绝加入走 {@code .../members/{memberId}/approve|reject} 需要 memberId，
     * 但 {@link #members} 只查 ACTIVE 成员、{@code /a/.../applications} 只列「建组」申请——
     * 既有接口都列不出某组的待审「加入」申请及其 memberId，前端因此拿不到 memberId、审批链在 API 层断裂。
     * 本方法补齐：返回 status=PENDING 且 role=普通成员 的行（排除建组时插的 PENDING 组长行）。</p>
     */
    public List<GroupMemberVO> joinApplications(Long groupId) {
        return joinApplicationsBy(groupId, currentVolunteerId());
    }

    /** 带显式 viewerId 的待审申请列表入口，供测试及需绕过 Sa-Token 上下文的场景调用。 */
    public List<GroupMemberVO> joinApplicationsBy(Long groupId, Long viewerId) {
        requireGroup(groupId);
        ensureLeaderOrAdminById(groupId, viewerId);
        List<VolunteerGroupMember> rows = memberMapper.selectList(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getGroupId, groupId)
                .eq(VolunteerGroupMember::getStatus, MEMBER_PENDING)
                .eq(VolunteerGroupMember::getRole, ROLE_MEMBER)
                .orderByAsc(VolunteerGroupMember::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(VolunteerGroupMember::getVolunteerId).distinct().toList();
        Map<Long, VolunteerDisplayView> displayById = volunteerQueryService.listDisplayByIds(ids);
        return rows.stream().map(m -> toMemberVO(m, displayById)).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void approveCreate(Long groupId, Long adminId) {
        VolunteerGroup group = requireGroup(groupId);
        LocalDateTime now = LocalDateTime.now();
        // CAS：仅当仍是 PENDING 时置 ACTIVE，affected=1 才算本次审批生效——
        // 防两个管理员并发 approve/reject 同一申请互相覆盖（rows!=1 即被对方抢先处理）。
        // wrapper 更新不触发 MetaObjectHandler，故显式 set update_time。
        int rows = groupMapper.update(null, Wrappers.<VolunteerGroup>lambdaUpdate()
                .set(VolunteerGroup::getStatus, GROUP_ACTIVE)
                .set(VolunteerGroup::getApprovedTime, now)
                .set(VolunteerGroup::getApprovedBy, adminId)
                .set(VolunteerGroup::getUpdateTime, now)
                .eq(VolunteerGroup::getId, groupId)
                .eq(VolunteerGroup::getStatus, GROUP_PENDING));
        if (rows != 1) {
            throw new BusinessException("小组不在待审核状态");
        }

        // 把 create() 阶段就插好的 PENDING 组长成员升级为 ACTIVE（CAS 命中后才执行）。
        // V7+ create() 必插 PENDING 组长行，故此处必命中；若缺失视为数据异常，明确报错——
        // 不再无校验兜底插入（旧兜底分支会绕过「一人一组」，已移除）。
        int upgraded = memberMapper.update(null, Wrappers.<VolunteerGroupMember>lambdaUpdate()
                .set(VolunteerGroupMember::getStatus, MEMBER_ACTIVE)
                .set(VolunteerGroupMember::getAuditTime, now)
                .set(VolunteerGroupMember::getAuditBy, adminId)
                .set(VolunteerGroupMember::getUpdateTime, now)
                .eq(VolunteerGroupMember::getGroupId, groupId)
                .eq(VolunteerGroupMember::getVolunteerId, group.getLeaderId())
                .eq(VolunteerGroupMember::getRole, ROLE_LEADER)
                .eq(VolunteerGroupMember::getStatus, MEMBER_PENDING));
        if (upgraded != 1) {
            throw new BusinessException("建组数据异常：未找到待生效的组长成员记录");
        }

        // 建组首次任命也算一次组长变更，作为历史起点
        recordLeaderChange(groupId, null, group.getLeaderId(), OP_TYPE_INITIAL, adminId, "建组审批通过首次任命");
    }

    @Transactional(rollbackFor = Exception.class)
    public void rejectCreate(Long groupId, String reason) {
        requireGroup(groupId);
        LocalDateTime now = LocalDateTime.now();
        // CAS：仅当仍 PENDING 时置 REJECTED；与 approveCreate 互斥，谁先命中谁定终态
        int rows = groupMapper.update(null, Wrappers.<VolunteerGroup>lambdaUpdate()
                .set(VolunteerGroup::getStatus, GROUP_REJECTED)
                .set(VolunteerGroup::getRejectReason, reason)
                .set(VolunteerGroup::getUpdateTime, now)
                .eq(VolunteerGroup::getId, groupId)
                .eq(VolunteerGroup::getStatus, GROUP_PENDING));
        if (rows != 1) {
            throw new BusinessException("小组不在待审核状态");
        }

        // 释放发起人在「一人一组」上的占用：把 create() 时插的 PENDING 组长成员置为已拒绝
        memberMapper.update(null, Wrappers.<VolunteerGroupMember>lambdaUpdate()
                .set(VolunteerGroupMember::getStatus, MEMBER_REJECTED)
                .set(VolunteerGroupMember::getAuditTime, now)
                .set(VolunteerGroupMember::getUpdateTime, now)
                .eq(VolunteerGroupMember::getGroupId, groupId)
                .eq(VolunteerGroupMember::getStatus, MEMBER_PENDING));
    }

    public void approveMember(Long groupId, Long memberId) {
        Long auditor = currentVolunteerId();
        ensureLeaderOrAdminById(groupId, auditor);
        approveMemberBy(groupId, memberId, auditor);
    }

    /**
     * 带显式 auditorId 的批准加入入口，供测试及后台管理员代操作绕过 Sa-Token 上下文。
     */
    public void approveMemberBy(Long groupId, Long memberId, Long auditorId) {
        VolunteerGroupMember member = requireMember(memberId);
        if (!Objects.equals(member.getGroupId(), groupId)) {
            throw new BusinessException("成员申请不属于该小组");
        }
        ensureNoOtherPendingOrActiveGroup(member.getVolunteerId(), member.getId());
        LocalDateTime now = LocalDateTime.now();
        // CAS：仅当仍 MEMBER_PENDING 时置 ACTIVE，affected=1 才算成功——防并发 approve/reject 覆盖
        int rows = memberMapper.update(null, Wrappers.<VolunteerGroupMember>lambdaUpdate()
                .set(VolunteerGroupMember::getStatus, MEMBER_ACTIVE)
                .set(VolunteerGroupMember::getAuditTime, now)
                .set(VolunteerGroupMember::getAuditBy, auditorId)
                .set(VolunteerGroupMember::getUpdateTime, now)
                .eq(VolunteerGroupMember::getId, memberId)
                .eq(VolunteerGroupMember::getGroupId, groupId)
                .eq(VolunteerGroupMember::getStatus, MEMBER_PENDING));
        if (rows != 1) {
            throw new BusinessException("成员申请不在待审核状态");
        }
    }

    public void rejectMember(Long groupId, Long memberId) {
        Long auditor = currentVolunteerId();
        ensureLeaderOrAdminById(groupId, auditor);
        rejectMemberBy(groupId, memberId, auditor);
    }

    /**
     * 带显式 auditorId 的拒绝入口，供测试及后台管理员代操作绕过 Sa-Token 上下文。
     */
    public void rejectMemberBy(Long groupId, Long memberId, Long auditorId) {
        VolunteerGroupMember member = requireMember(memberId);
        if (!Objects.equals(member.getGroupId(), groupId)) {
            throw new BusinessException("成员申请不属于该小组");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = memberMapper.update(null, Wrappers.<VolunteerGroupMember>lambdaUpdate()
                .set(VolunteerGroupMember::getStatus, MEMBER_REJECTED)
                .set(VolunteerGroupMember::getAuditTime, now)
                .set(VolunteerGroupMember::getAuditBy, auditorId)
                .set(VolunteerGroupMember::getUpdateTime, now)
                .eq(VolunteerGroupMember::getId, memberId)
                .eq(VolunteerGroupMember::getGroupId, groupId)
                .eq(VolunteerGroupMember::getStatus, MEMBER_PENDING));
        if (rows != 1) {
            throw new BusinessException("成员申请不在待审核状态");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long groupId, Long memberId) {
        ensureLeaderOrAdmin(groupId);
        VolunteerGroupMember member = requireMember(memberId);
        if (Objects.equals(member.getRole(), ROLE_LEADER)) {
            throw new BusinessException("不能移除组长");
        }
        member.setStatus(MEMBER_REMOVED);
        memberMapper.updateById(member);
    }

    /**
     * 组长指定一名 ACTIVE 成员为管理员（≤3 人）。仅组长可操作。
     * 不允许把组长自己设为管理员；不允许重复设置已是管理员的成员。
     */
    @Transactional(rollbackFor = Exception.class)
    public void setAdmin(Long groupId, Long memberId) {
        ensureCurrentLeader(groupId);
        VolunteerGroupMember member = requireMember(memberId);
        if (!Objects.equals(member.getGroupId(), groupId) || !Objects.equals(member.getStatus(), MEMBER_ACTIVE)) {
            throw new BusinessException("仅可对在册成员设置管理员");
        }
        if (Objects.equals(member.getRole(), ROLE_LEADER)) {
            throw new BusinessException("组长无需设置为管理员");
        }
        if (Objects.equals(member.getRole(), ROLE_ADMIN)) {
            throw new BusinessException("该成员已是管理员");
        }
        Long current = memberMapper.selectCount(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getGroupId, groupId)
                .eq(VolunteerGroupMember::getRole, ROLE_ADMIN)
                .eq(VolunteerGroupMember::getStatus, MEMBER_ACTIVE));
        if (current != null && current >= MAX_ADMIN_COUNT) {
            throw new BusinessException("管理员数量已达上限（" + MAX_ADMIN_COUNT + " 人）");
        }
        member.setRole(ROLE_ADMIN);
        memberMapper.updateById(member);
    }

    /** 组长取消某管理员，恢复为普通成员。仅组长可操作。 */
    @Transactional(rollbackFor = Exception.class)
    public void revokeAdmin(Long groupId, Long memberId) {
        ensureCurrentLeader(groupId);
        VolunteerGroupMember member = requireMember(memberId);
        if (!Objects.equals(member.getGroupId(), groupId) || !Objects.equals(member.getStatus(), MEMBER_ACTIVE)) {
            throw new BusinessException("仅可对在册管理员取消");
        }
        if (!Objects.equals(member.getRole(), ROLE_ADMIN)) {
            throw new BusinessException("该成员不是管理员");
        }
        member.setRole(ROLE_MEMBER);
        memberMapper.updateById(member);
    }

    @Transactional(rollbackFor = Exception.class)
    public void dissolve(Long groupId, String reason, Long adminId) {
        VolunteerGroup group = requireGroup(groupId);
        // 仅 ACTIVE 小组可解散：待审核应走 rejectCreate；已拒绝/已解散重复调用会覆盖 dissolve_* 字段，拒绝
        if (!Objects.equals(group.getStatus(), GROUP_ACTIVE)) {
            throw new BusinessException("仅正常状态的小组可解散");
        }
        LocalDateTime now = LocalDateTime.now();
        group.setStatus(GROUP_DISSOLVED);
        group.setDissolveTime(now);
        group.setDissolveReason(reason);
        group.setDissolveBy(adminId);
        groupMapper.updateById(group);

        // 解散同时清空成员关系，否则原成员仍被「一人一组」规则(ensureNoActiveGroup 按 PENDING/ACTIVE 统计)
        // 视为已有小组，无法加入/发起新组。待加入(PENDING)置为已拒绝，在册(ACTIVE)置为已移除。
        memberMapper.update(null, Wrappers.<VolunteerGroupMember>lambdaUpdate()
                .set(VolunteerGroupMember::getStatus, MEMBER_REJECTED)
                .set(VolunteerGroupMember::getAuditTime, now)
                .set(VolunteerGroupMember::getUpdateTime, now)
                .eq(VolunteerGroupMember::getGroupId, groupId)
                .eq(VolunteerGroupMember::getStatus, MEMBER_PENDING));
        memberMapper.update(null, Wrappers.<VolunteerGroupMember>lambdaUpdate()
                .set(VolunteerGroupMember::getStatus, MEMBER_REMOVED)
                .set(VolunteerGroupMember::getUpdateTime, now)
                .eq(VolunteerGroupMember::getGroupId, groupId)
                .eq(VolunteerGroupMember::getStatus, MEMBER_ACTIVE));
    }

    /**
     * 后台管理员转移组长。operatorType={@code 2}，写入组长变更历史。
     *
     * <p>新组长若原为管理员，提升为组长后管理员角色自动让位（role 字段唯一）；
     * 旧组长降为普通成员。如需保留旧组长的管理员身份，后续由当前组长手动 {@link #setAdmin}。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void transferLeader(Long groupId, Long volunteerId, Long adminId, String reason) {
        VolunteerGroup group = requireGroup(groupId);
        Long oldLeaderId = group.getLeaderId();
        VolunteerGroupMember next = memberMapper.selectOne(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getGroupId, groupId)
                .eq(VolunteerGroupMember::getVolunteerId, volunteerId)
                .eq(VolunteerGroupMember::getStatus, MEMBER_ACTIVE)
                .last("limit 1"));
        if (next == null) {
            throw new BusinessException("新组长必须是当前小组成员");
        }
        if (Objects.equals(oldLeaderId, volunteerId)) {
            throw new BusinessException("新组长不能与现任组长相同");
        }
        List<VolunteerGroupMember> leaders = memberMapper.selectList(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getGroupId, groupId)
                .eq(VolunteerGroupMember::getRole, ROLE_LEADER)
                .eq(VolunteerGroupMember::getStatus, MEMBER_ACTIVE));
        for (VolunteerGroupMember leader : leaders) {
            leader.setRole(ROLE_MEMBER);
            memberMapper.updateById(leader);
        }
        next.setRole(ROLE_LEADER);
        memberMapper.updateById(next);
        group.setLeaderId(volunteerId);
        groupMapper.updateById(group);

        recordLeaderChange(groupId, oldLeaderId, volunteerId, OP_TYPE_ADMIN_TRANSFER, adminId, reason);
    }

    /** 管理端：查询某小组的组长变更历史，按时间倒序。 */
    public List<GroupLeaderHistoryVO> leaderHistory(Long groupId) {
        requireGroup(groupId);
        List<VolunteerGroupLeaderHistory> rows = leaderHistoryMapper.selectList(
                Wrappers.<VolunteerGroupLeaderHistory>lambdaQuery()
                        .eq(VolunteerGroupLeaderHistory::getGroupId, groupId)
                        .orderByDesc(VolunteerGroupLeaderHistory::getChangeTime));
        if (rows.isEmpty()) {
            return List.of();
        }
        // 一次拉齐前后任组长姓名，避免 N+1
        Set<Long> ids = new HashSet<>();
        for (VolunteerGroupLeaderHistory h : rows) {
            if (h.getOldLeaderId() != null) {
                ids.add(h.getOldLeaderId());
            }
            ids.add(h.getNewLeaderId());
        }
        Map<Long, String> nameById = volunteerMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Volunteer::getId, Volunteer::getRealName));
        return rows.stream().map(h -> {
            GroupLeaderHistoryVO vo = new GroupLeaderHistoryVO();
            vo.setId(h.getId());
            vo.setOldLeaderId(h.getOldLeaderId());
            vo.setOldLeaderName(h.getOldLeaderId() == null ? null : nameById.get(h.getOldLeaderId()));
            vo.setNewLeaderId(h.getNewLeaderId());
            vo.setNewLeaderName(nameById.get(h.getNewLeaderId()));
            vo.setChangeTime(h.getChangeTime());
            vo.setOperatorType(h.getOperatorType());
            vo.setOperatorId(h.getOperatorId());
            vo.setReason(h.getReason());
            return vo;
        }).toList();
    }

    private void recordLeaderChange(Long groupId, Long oldLeaderId, Long newLeaderId,
                                    int operatorType, Long operatorId, String reason) {
        VolunteerGroupLeaderHistory h = new VolunteerGroupLeaderHistory();
        h.setGroupId(groupId);
        h.setOldLeaderId(oldLeaderId);
        h.setNewLeaderId(newLeaderId);
        h.setChangeTime(LocalDateTime.now());
        h.setOperatorType(operatorType);
        h.setOperatorId(operatorId);
        h.setReason(reason);
        leaderHistoryMapper.insert(h);
    }

    /**
     * 批量组装小组 VO：一次取组长姓名 + 一次按 group_id 聚合在册成员数，避免逐行 N+1。
     * 仅替换 records，分页 total/page/size 由调用方沿用原 page，语义不变。
     */
    private List<GroupVO> toGroupVOList(List<VolunteerGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        List<Long> leaderIds = groups.stream().map(VolunteerGroup::getLeaderId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> leaderNameById = volunteerQueryService.listNamesByIds(leaderIds);
        Map<Long, Long> memberCountById = countActiveMembersByGroup(
                groups.stream().map(VolunteerGroup::getId).toList());
        return groups.stream().map(g -> {
            GroupVO vo = new GroupVO();
            vo.setId(g.getId());
            vo.setGroupNo(g.getGroupNo());
            vo.setName(g.getName());
            vo.setDescription(g.getDescription());
            vo.setLeaderId(g.getLeaderId());
            vo.setLeaderName(g.getLeaderId() == null ? null : leaderNameById.get(g.getLeaderId()));
            vo.setStatus(g.getStatus());
            vo.setRejectReason(g.getRejectReason());
            vo.setCreateTime(g.getCreateTime());
            vo.setMemberCount(memberCountById.getOrDefault(g.getId(), 0L));
            return vo;
        }).toList();
    }

    /** 一次按 group_id 聚合在册(ACTIVE)成员数（selectMaps 仍自动带 is_deleted=0）。空 ids 早返回避免 in()。 */
    private Map<Long, Long> countActiveMembersByGroup(List<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = memberMapper.selectMaps(Wrappers.<VolunteerGroupMember>query()
                .select("group_id AS gid", "COUNT(*) AS cnt")
                .in("group_id", groupIds)
                .eq("status", MEMBER_ACTIVE)
                .groupBy("group_id"));
        Map<Long, Long> result = new HashMap<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.put(numOf(row, "gid"), numOf(row, "cnt"));
        }
        return result;
    }

    /** 从 selectMaps 行取数值（兼容驱动对别名大小写处理差异）。 */
    private static long numOf(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) {
            v = row.get(key.toUpperCase());
        }
        return v == null ? 0L : ((Number) v).longValue();
    }

    private GroupVO toGroupVO(VolunteerGroup group) {
        GroupVO vo = new GroupVO();
        vo.setId(group.getId());
        vo.setGroupNo(group.getGroupNo());
        vo.setName(group.getName());
        vo.setDescription(group.getDescription());
        vo.setLeaderId(group.getLeaderId());
        Volunteer leader = volunteerMapper.selectById(group.getLeaderId());
        vo.setLeaderName(leader == null ? null : leader.getRealName());
        vo.setStatus(group.getStatus());
        vo.setRejectReason(group.getRejectReason());
        vo.setCreateTime(group.getCreateTime());
        vo.setMemberCount(memberMapper.selectCount(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getGroupId, group.getId())
                .eq(VolunteerGroupMember::getStatus, MEMBER_ACTIVE)));
        return vo;
    }

    private GroupMemberVO toMemberVO(VolunteerGroupMember member, Map<Long, VolunteerDisplayView> displayById) {
        GroupMemberVO vo = new GroupMemberVO();
        vo.setId(member.getId());
        vo.setVolunteerId(member.getVolunteerId());
        VolunteerDisplayView d = displayById.get(member.getVolunteerId());
        if (d != null) {
            vo.setRealName(d.realName());
            vo.setSchool(d.school());
            vo.setPhone(d.phone());
        }
        vo.setRole(member.getRole());
        vo.setStatus(member.getStatus());
        vo.setApplyTime(member.getApplyTime());
        return vo;
    }

    private VolunteerGroup requireGroup(Long id) {
        VolunteerGroup group = groupMapper.selectById(id);
        if (group == null) {
            throw new BusinessException("小组不存在");
        }
        return group;
    }

    private VolunteerGroupMember requireMember(Long id) {
        VolunteerGroupMember member = memberMapper.selectById(id);
        if (member == null) {
            throw new BusinessException("成员不存在");
        }
        return member;
    }

    private VolunteerGroupMember currentActiveMember(Long groupId, Long volunteerId) {
        VolunteerGroupMember member = memberMapper.selectOne(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getGroupId, groupId)
                .eq(VolunteerGroupMember::getVolunteerId, volunteerId)
                .eq(VolunteerGroupMember::getStatus, MEMBER_ACTIVE)
                .last("limit 1"));
        if (member == null) {
            throw new BusinessException("不是该小组成员");
        }
        return member;
    }

    private void ensureCurrentLeader(Long groupId) {
        VolunteerGroupMember member = currentActiveMember(groupId, currentVolunteerId());
        if (!Objects.equals(member.getRole(), ROLE_LEADER)) {
            throw new BusinessException("仅小组负责人可操作");
        }
    }

    /**
     * 校验当前志愿者是该小组的组长或管理员，返回其 volunteerId 作为审批人 audit_by。
     * 用于审批加入、移除成员等"日常运营"动作。
     */
    private Long ensureLeaderOrAdmin(Long groupId) {
        Long volunteerId = currentVolunteerId();
        ensureLeaderOrAdminById(groupId, volunteerId);
        return volunteerId;
    }

    /** 用显式 volunteerId 校验组长/管理员，供测试及需要绕过 Sa-Token 上下文的入口调用。 */
    private void ensureLeaderOrAdminById(Long groupId, Long volunteerId) {
        VolunteerGroupMember member = currentActiveMember(groupId, volunteerId);
        if (!Objects.equals(member.getRole(), ROLE_LEADER) && !Objects.equals(member.getRole(), ROLE_ADMIN)) {
            throw new BusinessException("仅小组负责人或管理员可操作");
        }
    }

    private void ensureNoActiveGroup(Long volunteerId) {
        Long count = memberMapper.selectCount(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getVolunteerId, volunteerId)
                .in(VolunteerGroupMember::getStatus, MEMBER_PENDING, MEMBER_ACTIVE));
        if (count > 0) {
            throw new BusinessException("一个志愿者只能加入一个小组");
        }
    }

    private void ensureNoOtherPendingOrActiveGroup(Long volunteerId, Long currentMemberId) {
        Long count = memberMapper.selectCount(Wrappers.<VolunteerGroupMember>lambdaQuery()
                .eq(VolunteerGroupMember::getVolunteerId, volunteerId)
                .ne(VolunteerGroupMember::getId, currentMemberId)
                .in(VolunteerGroupMember::getStatus, MEMBER_PENDING, MEMBER_ACTIVE));
        if (count > 0) {
            throw new BusinessException("一个志愿者只能加入一个小组");
        }
    }

    private void ensureNormalVolunteer(Long volunteerId) {
        Volunteer volunteer = volunteerMapper.selectById(volunteerId);
        if (volunteer == null || !Integer.valueOf(0).equals(volunteer.getStatus())) {
            throw new BusinessException("志愿者账号不可用");
        }
    }

    private Long currentVolunteerId() {
        return StpUtil.getLoginIdAsLong();
    }

    /**
     * 在「志愿者维度」分布式锁内执行动作，关闭 create/join 的 read-then-insert 竞态。
     *
     * <p>锁前缀 {@code lock:group:volunteer:} 与 EnrollmentService 的 {@code lock:enroll:volunteer:} 隔离，
     * 二者锁不同领域的操作、互不抢占——同一志愿者可同时被同步报名+建组，但两个建组请求会被串行化。</p>
     *
     * <p>不指定 leaseTime：走 Redisson watchdog 自动续期，避免「事务未提交锁已到期」窗口。
     * 调用约定：supplier 内必须用 TransactionTemplate 包出事务，提交后才会回到 finally 释放锁。</p>
     */
    private <T> T runLocked(Long volunteerId, Supplier<T> action) {
        RLock lock = redissonClient.getLock("lock:group:volunteer:" + volunteerId);
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("操作被中断，请重试");
        }
        if (!locked) {
            throw new BusinessException("操作太频繁，请稍后再试");
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
