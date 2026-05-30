package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.dto.ActivityCreateDTO;
import com.hengde.activity.dto.ActivitySlotDTO;
import com.hengde.activity.dto.ActivityUpdateDTO;
import com.hengde.activity.dto.RecurringActivityDTO;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.activity.vo.ActivityAdminDetailVO;
import com.hengde.activity.vo.ActivityListVO;
import com.hengde.activity.vo.ActivitySlotVO;
import com.hengde.activity.vo.ActivityVolunteerDetailVO;
import com.hengde.activity.vo.RecommendActivityVO;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.search.SearchItemVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 活动发布/管理（管理端）。
 *
 * <p>V1 取舍：创建即发布（status=1），不做草稿流程；编号 serial_no 发布时取 id（自增、唯一递增，
 * 零竞态，暂不引 Redisson）；时间段全量替换；已结束/已取消不可改。</p>
 *
 * @author hengde
 */
@Service
public class ActivityService {

    /** 已发布 */
    private static final int STATUS_PUBLISHED = 1;
    /** 已结束 */
    private static final int STATUS_FINISHED = 2;
    /** 已取消 */
    private static final int STATUS_CANCELLED = 3;

    private static final BigDecimal DEFAULT_LEADER_MULTIPLIER = new BigDecimal("1.4");
    private static final BigDecimal DEFAULT_MANAGER_MULTIPLIER = new BigDecimal("1.2");

    /** 周期发布单次批量上限（防失控） */
    private static final int MAX_RECURRING = 60;

    /** 周期规则展开的最大跨度天数（防误填年份导致长循环占线程） */
    private static final long MAX_RECURRING_SPAN_DAYS = 366;

    private ActivityMapper activityMapper;
    private ActivitySlotMapper activitySlotMapper;
    private ActivityEnrollmentMapper activityEnrollmentMapper;

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setActivitySlotMapper(ActivitySlotMapper activitySlotMapper) {
        this.activitySlotMapper = activitySlotMapper;
    }

    @Autowired
    public void setActivityEnrollmentMapper(ActivityEnrollmentMapper activityEnrollmentMapper) {
        this.activityEnrollmentMapper = activityEnrollmentMapper;
    }

    /**
     * 发布活动（创建即发布）。
     */
    @Transactional
    public Long publish(ActivityCreateDTO dto, Long adminId) {
        return publishOne(dto, adminId);
    }

    /**
     * 固定日期周期发布：以一份模板按多个目标日批量发布多场活动（第 3 批·PR2）。
     *
     * <p>目标日 = 显式日期 ∪ 周期规则展开，去重排序；空集合或超 {@value #MAX_RECURRING} 场拒绝。
     * 每个目标日按相对锚点日（模板 startTime 的日期）的天数差整体平移模板的全部日期型字段后走
     * {@link #publishOne}（逐场过 {@link #validateDto}、各自分配编号）。整批单事务，全成或全败。</p>
     *
     * @return 新建活动 id（按目标日升序）
     */
    @Transactional
    public List<Long> publishRecurring(RecurringActivityDTO dto, Long adminId) {
        if (dto == null || dto.getTemplate() == null) {
            throw new BusinessException("活动模板不能为空");
        }
        ActivityCreateDTO template = dto.getTemplate();
        if (template.getStartTime() == null || template.getEndTime() == null) {
            throw new BusinessException("活动模板开始/结束时间不能为空");
        }
        if (template.getSlots() == null || template.getSlots().isEmpty()) {
            throw new BusinessException("活动模板至少需要一个时间段");
        }
        List<LocalDate> targetDates = resolveTargetDates(dto);
        LocalDate anchorDate = template.getStartTime().toLocalDate();

        List<Long> ids = new ArrayList<>(targetDates.size());
        for (LocalDate d : targetDates) {
            long dayShift = ChronoUnit.DAYS.between(anchorDate, d);
            ids.add(publishOne(shiftTemplate(template, dayShift), adminId));
        }
        return ids;
    }

    /** 发布单场活动（创建即发布）：校验 + 默认值 + 插入 + 编号=自增 id + 时间段。供 publish/publishRecurring 共用。 */
    private Long publishOne(ActivityCreateDTO dto, Long adminId) {
        validateDto(dto);

        Activity activity = new Activity();
        BeanUtils.copyProperties(dto, activity, "slots");
        applyDefaults(activity);
        activity.setStatus(STATUS_PUBLISHED);
        activity.setCreateBy(adminId);
        activityMapper.insert(activity);

        // 编号 = 自增 id（唯一递增，零竞态）
        activity.setSerialNo(activity.getId());
        activityMapper.updateById(activity);

        insertSlots(activity.getId(), dto.getSlots());
        return activity.getId();
    }

    /** 解析周期发布目标日集合：显式日期 ∪ 规则展开，去重排序 + 非空/跨度/上限校验。 */
    private List<LocalDate> resolveTargetDates(RecurringActivityDTO dto) {
        TreeSet<LocalDate> set = new TreeSet<>();
        if (dto.getDates() != null) {
            for (LocalDate d : dto.getDates()) {
                if (d == null) {
                    throw new BusinessException("发布日期不能为空");
                }
                set.add(d);
            }
            if (set.size() > MAX_RECURRING) {
                throw new BusinessException("批量发布场次过多（上限 " + MAX_RECURRING + " 场）");
            }
        }
        boolean hasRule = dto.getRecurStart() != null || dto.getRecurEnd() != null
                || (dto.getWeekdays() != null && !dto.getWeekdays().isEmpty());
        if (hasRule) {
            if (dto.getRecurStart() == null || dto.getRecurEnd() == null
                    || dto.getWeekdays() == null || dto.getWeekdays().isEmpty()) {
                throw new BusinessException("周期规则需同时提供起始日期、结束日期与星期几");
            }
            if (dto.getRecurEnd().isBefore(dto.getRecurStart())) {
                throw new BusinessException("周期结束日期不能早于起始日期");
            }
            if (ChronoUnit.DAYS.between(dto.getRecurStart(), dto.getRecurEnd()) > MAX_RECURRING_SPAN_DAYS) {
                throw new BusinessException("周期跨度过大（上限 " + MAX_RECURRING_SPAN_DAYS + " 天）");
            }
            for (Integer w : dto.getWeekdays()) {
                if (w == null || w < 1 || w > 7) {
                    throw new BusinessException("星期几取值 1~7（1周一…7周日）");
                }
            }
            Set<Integer> wd = new HashSet<>(dto.getWeekdays());
            for (LocalDate d = dto.getRecurStart(); !d.isAfter(dto.getRecurEnd()); d = d.plusDays(1)) {
                if (wd.contains(d.getDayOfWeek().getValue())) {
                    set.add(d);
                    if (set.size() > MAX_RECURRING) {
                        throw new BusinessException("批量发布场次过多（上限 " + MAX_RECURRING + " 场）");
                    }
                }
            }
        }
        if (set.isEmpty()) {
            throw new BusinessException("未解析到任何发布日期（请提供显式日期或完整周期规则）");
        }
        return new ArrayList<>(set);
    }

    /** 克隆模板并把全部日期型字段（含各时间段）平移 dayShift 天，时刻不变（中国无 DST）。 */
    private ActivityCreateDTO shiftTemplate(ActivityCreateDTO template, long dayShift) {
        ActivityCreateDTO c = new ActivityCreateDTO();
        BeanUtils.copyProperties(template, c);   // LocalDateTime 不可变；slots 为共享引用，下方整体覆盖
        c.setStartTime(shiftDays(template.getStartTime(), dayShift));
        c.setEndTime(shiftDays(template.getEndTime(), dayShift));
        c.setEnrollDeadline(shiftDays(template.getEnrollDeadline(), dayShift));
        c.setCancelDeadline(shiftDays(template.getCancelDeadline(), dayShift));
        c.setEnrollOpenManager(shiftDays(template.getEnrollOpenManager(), dayShift));
        c.setEnrollOpenLeader(shiftDays(template.getEnrollOpenLeader(), dayShift));
        c.setEnrollOpenVolunteer(shiftDays(template.getEnrollOpenVolunteer(), dayShift));

        List<ActivitySlotDTO> slots = new ArrayList<>();
        for (ActivitySlotDTO s : template.getSlots()) {
            ActivitySlotDTO ns = new ActivitySlotDTO();
            BeanUtils.copyProperties(s, ns);
            ns.setStartTime(shiftDays(s.getStartTime(), dayShift));
            ns.setEndTime(shiftDays(s.getEndTime(), dayShift));
            slots.add(ns);
        }
        c.setSlots(slots);
        return c;
    }

    private LocalDateTime shiftDays(LocalDateTime t, long dayShift) {
        return t == null ? null : t.plusDays(dayShift);
    }

    /**
     * 修改活动（全量更新 + 时间段全量替换）。
     */
    @Transactional
    public void update(Long id, ActivityUpdateDTO dto) {
        Activity activity = activityMapper.selectById(id);
        if (activity == null) {
            throw new BusinessException("活动不存在");
        }
        if (Integer.valueOf(STATUS_FINISHED).equals(activity.getStatus())
                || Integer.valueOf(STATUS_CANCELLED).equals(activity.getStatus())) {
            throw new BusinessException("已结束或已取消的活动不可修改");
        }
        // 时间段全量替换会让已有报名的 slot_id 失效（详情/审核/导出断链），故有报名记录时禁止修改。
        // 待报名模块落地后改为「按 slot id 增改删 + 仅删无报名的 slot」。
        if (hasEnrollment(id)) {
            throw new BusinessException("该活动已有报名记录，暂不支持修改（含时间段）");
        }
        validateDto(dto);

        BeanUtils.copyProperties(dto, activity, "slots");
        applyDefaults(activity);
        activityMapper.updateById(activity);

        activitySlotMapper.delete(Wrappers.<ActivitySlot>lambdaQuery()
                .eq(ActivitySlot::getActivityId, id));
        insertSlots(id, dto.getSlots());
    }

    /**
     * 删除活动（逻辑删除活动及其时间段）。
     */
    @Transactional
    public void delete(Long id) {
        Activity activity = activityMapper.selectById(id);
        if (activity == null) {
            throw new BusinessException("活动不存在");
        }
        if (Integer.valueOf(STATUS_FINISHED).equals(activity.getStatus())) {
            throw new BusinessException("已结束的活动不可删除");
        }
        // 有报名记录则不可直接删（会断报名数据链路），应改为取消活动
        if (hasEnrollment(id)) {
            throw new BusinessException("该活动已有报名记录，不可删除，请改为取消活动");
        }
        activityMapper.deleteById(id);
        activitySlotMapper.delete(Wrappers.<ActivitySlot>lambdaQuery()
                .eq(ActivitySlot::getActivityId, id));
    }

    /** 该活动是否存在报名记录（任意状态，含已取消/已拒绝——它们仍引用 slot 且出现在报名列表/导出）。 */
    private boolean hasEnrollment(Long activityId) {
        Long count = activityEnrollmentMapper.selectCount(Wrappers.<ActivityEnrollment>lambdaQuery()
                .eq(ActivityEnrollment::getActivityId, activityId));
        return count != null && count > 0;
    }

    /**
     * 复制活动：深拷贝活动 + 时间段为一条新的已发布活动，分配新编号，标题加「（复制）」。
     */
    @Transactional
    public Long copy(Long id, Long adminId) {
        Activity src = activityMapper.selectById(id);
        if (src == null) {
            throw new BusinessException("活动不存在");
        }
        if (Integer.valueOf(STATUS_CANCELLED).equals(src.getStatus())) {
            throw new BusinessException("已取消的活动不可复制");
        }
        Activity copy = new Activity();
        BeanUtils.copyProperties(src, copy);
        copy.setId(null);
        copy.setSerialNo(null);
        copy.setCreateTime(null);
        copy.setUpdateTime(null);
        copy.setIsDeleted(null);
        copy.setStatus(STATUS_PUBLISHED);
        copy.setCreateBy(adminId);
        copy.setTitle(src.getTitle() + "（复制）");
        activityMapper.insert(copy);
        copy.setSerialNo(copy.getId());
        activityMapper.updateById(copy);

        List<ActivitySlot> srcSlots = activitySlotMapper.selectList(Wrappers.<ActivitySlot>lambdaQuery()
                .eq(ActivitySlot::getActivityId, id));
        for (ActivitySlot s : srcSlots) {
            ActivitySlot ns = new ActivitySlot();
            BeanUtils.copyProperties(s, ns);
            ns.setId(null);
            ns.setCreateTime(null);
            ns.setUpdateTime(null);
            ns.setIsDeleted(null);
            ns.setActivityId(copy.getId());
            activitySlotMapper.insert(ns);
        }
        return copy.getId();
    }

    /**
     * 志愿者端「推荐活动」列表：仅已发布，<b>有名额优先、否则按最新活动时间</b>排序，
     * 并带出报名人数(enrolledCount)与有名额标记(hasQuota)。排序与名额计算在 DB 层完成
     * （{@link ActivityMapper#selectRecommendPage}），保证跨分页正确。
     */
    public PageResult<RecommendActivityVO> listForVolunteer(PageQuery query, String keyword) {
        Page<RecommendActivityVO> page = query.toPage();
        activityMapper.selectRecommendPage(page, StringUtils.hasText(keyword) ? keyword : null);
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 全局搜索：已发布活动按标题匹配的命中总数（供 api 聚合层算精确分页 total）。 */
    public long countSearch(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return 0;
        }
        Long c = activityMapper.selectCount(Wrappers.<Activity>lambdaQuery()
                .eq(Activity::getStatus, STATUS_PUBLISHED)
                .like(Activity::getTitle, keyword));
        return c == null ? 0 : c;
    }

    /** 全局搜索：已发布活动按标题匹配，取 [offset, offset+limit) 窗口（供 api 聚合层跨领域分页）。 */
    public List<SearchItemVO> search(String keyword, int offset, int limit) {
        if (!StringUtils.hasText(keyword) || limit <= 0) {
            return List.of();
        }
        List<Activity> list = activityMapper.selectList(Wrappers.<Activity>lambdaQuery()
                .eq(Activity::getStatus, STATUS_PUBLISHED)
                .like(Activity::getTitle, keyword)
                .orderByDesc(Activity::getId)
                .last("limit " + offset + "," + limit));
        return list.stream()
                .map(a -> new SearchItemVO("activity", a.getId(), a.getTitle(), null, a.getCoverImageUrl()))
                .toList();
    }

    /**
     * 管理端活动列表：可选状态筛选 + 标题关键词，按创建时间倒序。
     */
    public PageResult<ActivityListVO> listForAdmin(PageQuery query, String keyword, Integer status) {
        Page<Activity> page = query.toPage();
        var wrapper = Wrappers.<Activity>lambdaQuery();
        if (status != null) {
            wrapper.eq(Activity::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Activity::getTitle, keyword);
        }
        wrapper.orderByDesc(Activity::getCreateTime);
        activityMapper.selectPage(page, wrapper);
        return toListResult(page);
    }

    /**
     * 管理端活动详情（不限状态，全量字段）。
     */
    public ActivityAdminDetailVO detailForAdmin(Long id) {
        Activity activity = activityMapper.selectById(id);
        if (activity == null) {
            throw new BusinessException("活动不存在");
        }
        ActivityAdminDetailVO vo = new ActivityAdminDetailVO();
        BeanUtils.copyProperties(activity, vo);
        vo.setSlots(loadSlotVOs(id));
        return vo;
    }

    /**
     * 志愿者端活动详情：仅已发布可见（按 id 直达非已发布一律视为不存在）；不含管理字段。
     */
    public ActivityVolunteerDetailVO detailForVolunteer(Long id) {
        Activity activity = activityMapper.selectById(id);
        if (activity == null || !Integer.valueOf(STATUS_PUBLISHED).equals(activity.getStatus())) {
            throw new BusinessException("活动不存在");
        }
        ActivityVolunteerDetailVO vo = new ActivityVolunteerDetailVO();
        BeanUtils.copyProperties(activity, vo);
        vo.setSlots(loadSlotVOs(id));
        return vo;
    }

    private List<ActivitySlotVO> loadSlotVOs(Long activityId) {
        List<ActivitySlot> slots = activitySlotMapper.selectList(Wrappers.<ActivitySlot>lambdaQuery()
                .eq(ActivitySlot::getActivityId, activityId)
                .orderByAsc(ActivitySlot::getStartTime));
        return slots.stream().map(this::toSlotVO).toList();
    }

    private PageResult<ActivityListVO> toListResult(Page<Activity> page) {
        List<ActivityListVO> records = page.getRecords().stream().map(a -> {
            ActivityListVO vo = new ActivityListVO();
            BeanUtils.copyProperties(a, vo);
            return vo;
        }).toList();
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    private ActivitySlotVO toSlotVO(ActivitySlot slot) {
        ActivitySlotVO vo = new ActivitySlotVO();
        BeanUtils.copyProperties(slot, vo);
        return vo;
    }

    private void insertSlots(Long activityId, List<ActivitySlotDTO> slots) {
        for (ActivitySlotDTO dto : slots) {
            ActivitySlot slot = new ActivitySlot();
            BeanUtils.copyProperties(dto, slot);
            slot.setActivityId(activityId);
            if (slot.getNeedCount() == null) {
                slot.setNeedCount(0);
            }
            activitySlotMapper.insert(slot);
        }
    }

    private void applyDefaults(Activity activity) {
        if (activity.getEnrollDeadline() == null) {
            activity.setEnrollDeadline(activity.getStartTime().minusHours(24));
        }
        if (activity.getLeaderMultiplier() == null) {
            activity.setLeaderMultiplier(DEFAULT_LEADER_MULTIPLIER);
        }
        if (activity.getManagerMultiplier() == null) {
            activity.setManagerMultiplier(DEFAULT_MANAGER_MULTIPLIER);
        }
        if (activity.getPointsBase() == null) {
            activity.setPointsBase(0);
        }
        if (activity.getNeedAudit() == null) {
            activity.setNeedAudit(0);
        }
        if (activity.getEnrollScope() == null) {
            activity.setEnrollScope(0);
        }
        if (activity.getRequireMinJoinCount() == null) {
            activity.setRequireMinJoinCount(0);
        }
        if (activity.getMinProjects() == null) {
            activity.setMinProjects(0);
        }
        if (activity.getNoticeCountdownSec() == null) {
            activity.setNoticeCountdownSec(0);
        }
        // 有坐标才有意义设半径；半径不填默认 500m（与 DB 默认一致，显式落值便于管理端回显）
        if (activity.getCheckInRadiusM() == null) {
            activity.setCheckInRadiusM(500);
        }
    }

    /** 入参业务校验：时间合法性 + 报名/取消截止 + 跨字段边界（Bean Validation 管不到的部分）。 */
    private void validateDto(ActivityCreateDTO dto) {
        LocalDateTime start = dto.getStartTime();
        LocalDateTime end = dto.getEndTime();
        if (!start.isBefore(end)) {
            throw new BusinessException("活动开始时间必须早于结束时间");
        }
        // 指定分队报名依赖分队模块（排期在 activity 之后），V1 尚不能真正限定。
        // 直接拒绝 enrollScope=1，避免「看似限定分队、实际全平台放行」的配置错觉；分队模块就绪后放开并校验 targetSquadIds。
        if (dto.getEnrollScope() != null && dto.getEnrollScope() == 1) {
            throw new BusinessException("V1 暂不支持指定分队报名，enrollScope 仅可为 0");
        }
        // GPS 签到坐标：经纬度须同时提供或同时留空（留空=本活动不启用 GPS 签到）
        if ((dto.getLat() == null) != (dto.getLng() == null)) {
            throw new BusinessException("签到坐标经纬度必须同时填写");
        }
        if (dto.getEnrollDeadline() != null && dto.getEnrollDeadline().isAfter(start)) {
            throw new BusinessException("报名截止时间不能晚于活动开始时间");
        }
        if (dto.getCancelDeadline() != null && dto.getCancelDeadline().isAfter(start)) {
            throw new BusinessException("取消报名截止时间不能晚于活动开始时间");
        }
        if (dto.getRequireMinAge() != null && dto.getRequireMaxAge() != null
                && dto.getRequireMinAge() > dto.getRequireMaxAge()) {
            throw new BusinessException("最小年龄不能大于最大年龄");
        }
        if (dto.getRequireMinGrade() != null && dto.getRequireMaxGrade() != null
                && dto.getRequireMinGrade() > dto.getRequireMaxGrade()) {
            throw new BusinessException("最低年级不能高于最高年级");
        }
        if (dto.getMinProjects() != null && dto.getMaxProjects() != null
                && dto.getMinProjects() > dto.getMaxProjects()) {
            throw new BusinessException("最少报名项目数不能大于最多报名项目数");
        }
        for (ActivitySlotDTO slot : dto.getSlots()) {
            if (!slot.getStartTime().isBefore(slot.getEndTime())) {
                throw new BusinessException("时间段「" + slot.getProjectName() + "」开始时间必须早于结束时间");
            }
            if (slot.getStartTime().isBefore(start) || slot.getEndTime().isAfter(end)) {
                throw new BusinessException("时间段「" + slot.getProjectName() + "」必须落在活动整体时间范围内");
            }
        }
    }
}
