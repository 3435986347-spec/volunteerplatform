package com.hengde.honor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.service.ActivityRankingQueryService;
import com.hengde.activity.service.PointService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerFlagInfoView;
import com.hengde.auth.vo.VolunteerGrantEligibilityView;
import com.hengde.common.constant.UserStatus;
import com.hengde.common.exception.BusinessException;
import com.hengde.honor.constant.MedalConditionType;
import com.hengde.honor.constant.MedalGrantStatus;
import com.hengde.honor.constant.MedalStatus;
import com.hengde.honor.dao.HonorMedalGrantMapper;
import com.hengde.honor.dto.MedalGrantDTO;
import com.hengde.honor.entity.HonorMedal;
import com.hengde.honor.entity.HonorMedalGrant;
import com.hengde.honor.vo.MedalGrantVO;
import com.hengde.honor.vo.MyMedalVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 勋章发放：发起 → 发放审核 → 生效。
 *
 * <p><b>双重审核的第二重</b>。发起只落「待审核」，志愿者端看不到；审核通过才生效，
 * 附带积分也在生效那一刻入账——未生效就发分等于绕过审核给了实际权益。</p>
 *
 * <p><b>防重复授予</b>靠 DB 生成列唯一键 {@code uk_active_grant}（待审/已生效时取
 * {@code medal-volunteer}，驳回后为 NULL）。放在数据库而不是「先查再插」，
 * 是因为后者在并发下必然漏（两个管理员同时发放同一枚勋章给同一人）。
 * <b>驳回的记录不占用唯一键</b>，所以驳回之后可以重新发起——用死约束会把人永久挡在门外。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class MedalGrantService {

    private HonorMedalGrantMapper grantMapper;
    private MedalService medalService;
    private VolunteerQueryService volunteerQueryService;
    private ActivityRankingQueryService rankingQueryService;
    private PointService pointService;

    @Autowired
    public void setGrantMapper(HonorMedalGrantMapper grantMapper) {
        this.grantMapper = grantMapper;
    }

    @Autowired
    public void setMedalService(MedalService medalService) {
        this.medalService = medalService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setRankingQueryService(ActivityRankingQueryService rankingQueryService) {
        this.rankingQueryService = rankingQueryService;
    }

    @Autowired
    public void setPointService(PointService pointService) {
        this.pointService = pointService;
    }

    // ================= 发放 =================

    /**
     * 发起发放，落<b>待审核</b>。
     *
     * @param dto     勋章 + 志愿者 + 理由
     * @param adminId 发起人
     * @return 发放记录 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long apply(MedalGrantDTO dto, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("发起人不能为空");
        }
        // 共享锁当前读：与「删除勋章」（排他锁）互斥，杜绝「查到已启用 → 对方删掉 → 这边插入」
        // 产生的孤儿发放记录（V28 无外键，数据库不会替我们拦）
        HonorMedal medal = medalService.requireMedalForShare(dto.getMedalId());
        // 样式未过审 / 已停用的勋章不能发——这正是「样式审核」这一重的意义所在
        if (!Integer.valueOf(MedalStatus.ENABLED).equals(medal.getStatus())) {
            throw new BusinessException("该勋章尚未通过样式审核或已停用，不能发放（当前："
                    + MedalStatus.labelOf(medal.getStatus()) + "）");
        }
        requireRegisteredVolunteer(dto.getVolunteerId());

        HonorMedalGrant grant = new HonorMedalGrant();
        grant.setMedalId(medal.getId());
        grant.setVolunteerId(dto.getVolunteerId());
        grant.setGrantType(1);
        // 快照当下的积分奖励值：申请与审核之间若定义被改，审核人批准的仍是他看到的那个数
        grant.setRewardPoints(medal.getRewardPoints() == null ? 0 : medal.getRewardPoints());
        grant.setReason(truncate(dto.getReason()));
        grant.setStatus(MedalGrantStatus.PENDING);
        grant.setApplyBy(adminId);
        grant.setApplyTime(LocalDateTime.now());
        try {
            grantMapper.insert(grant);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该志愿者已获得此勋章或已有待审核的发放记录");
        }
        return grant.getId();
    }

    /**
     * 发放审核通过：待审核 → 已生效；附带积分同事务入账。
     *
     * <p>顺序钉死：先校验勋章仍可用 → <b>CAS</b> 置生效 → 再入账。
     * CAS 成功才代表本次确实是「首次生效」，据此发分不会重复；
     * 反过来先发分再 CAS，CAS 失败时积分已经进了账本（虽然 {@code uk_source} 会挡住
     * 第二次，但第一次的分就白发了）。</p>
     *
     * <p><b>勋章与志愿者两侧都要在此刻复核，且都必须是当前读</b>。权益是在审核这一刻产生的，
     * 不是发起那一刻——发起时校验过的「勋章已启用」「志愿者已实名且账号正常」，到这里都可能已经变了
     * （发起与审核之间往往隔着好几天）。少了志愿者那一侧，一个已被禁用/注销/删除的账号照样能拿到
     * 勋章并入账积分。</p>
     *
     * <p><b>「勋章仍可用」这一步必须是当前读</b>（{@code FOR SHARE}）。本方法在事务内，
     * 而取发放记录那条普通查询已经把 REPEATABLE READ 的读视图定死；此后即使另一个事务把勋章
     * 停用或退回重审<b>并提交</b>，再用普通查询读勋章仍会读到旧的「已启用」，于是一枚已经被撤下的
     * 勋章照样生效、照样发分。共享锁读到最新已提交值，并把停用/修改挡在事务提交之外。</p>
     *
     * @param grantId 发放记录 id
     * @param adminId 审核人
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long grantId, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("审核人不能为空");
        }
        HonorMedalGrant grant = requireGrant(grantId);
        HonorMedal medal = medalService.requireMedalForShare(grant.getMedalId());
        // 发起之后勋章可能被停用/退回重审，此时不该再让它生效——与活动补录落账前再查一次同理
        if (!Integer.valueOf(MedalStatus.ENABLED).equals(medal.getStatus())) {
            throw new BusinessException("该勋章当前为「" + MedalStatus.labelOf(medal.getStatus())
                    + "」，不能通过发放；请先恢复启用");
        }
        // 志愿者侧同样要复核，理由完全一致：权益是在这一刻产生的，不是发起那一刻
        requireGrantableVolunteerForShare(grant.getVolunteerId());

        LocalDateTime now = LocalDateTime.now();
        int rows = grantMapper.update(null, Wrappers.<HonorMedalGrant>lambdaUpdate()
                .set(HonorMedalGrant::getStatus, MedalGrantStatus.EFFECTIVE)
                .set(HonorMedalGrant::getRejectReason, null)
                .set(HonorMedalGrant::getReviewBy, adminId)
                .set(HonorMedalGrant::getReviewTime, now)
                .set(HonorMedalGrant::getUpdateTime, now)
                .eq(HonorMedalGrant::getId, grantId)
                .eq(HonorMedalGrant::getStatus, MedalGrantStatus.PENDING));
        if (rows != 1) {
            throw new BusinessException("发放记录不存在或已被处理，请刷新重试");
        }

        int reward = grant.getRewardPoints() == null ? 0 : grant.getRewardPoints();
        if (reward > 0) {
            // 来源码 MEDAL(3) 自 V24 起就为此预留；source_id 用发放记录 id，
            // uk_source(3, grantId) 保证同一次发放只入账一次
            pointService.record(grant.getVolunteerId(), reward, PointSourceType.MEDAL, grantId,
                    "获得勋章「" + medal.getName() + "」", PointSourceType.OPERATOR_ADMIN, adminId);
        }
    }

    /**
     * 发放审核驳回：待审核 → 已驳回。不生效、不发分，但留痕；之后可对同一人重新发起。
     *
     * @param grantId 发放记录 id
     * @param reason  驳回原因
     * @param adminId 审核人
     */
    public void reject(Long grantId, String reason, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("审核人不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = grantMapper.update(null, Wrappers.<HonorMedalGrant>lambdaUpdate()
                .set(HonorMedalGrant::getStatus, MedalGrantStatus.REJECTED)
                .set(HonorMedalGrant::getRejectReason, truncate(reason))
                .set(HonorMedalGrant::getReviewBy, adminId)
                .set(HonorMedalGrant::getReviewTime, now)
                .set(HonorMedalGrant::getUpdateTime, now)
                .eq(HonorMedalGrant::getId, grantId)
                .eq(HonorMedalGrant::getStatus, MedalGrantStatus.PENDING));
        if (rows != 1) {
            throw new BusinessException("发放记录不存在或已被处理，请刷新重试");
        }
    }

    // ================= 查询 =================

    /**
     * 后台发放记录列表。
     *
     * @param status      状态筛选；null=全部
     * @param volunteerId 志愿者筛选；null=不限
     * @return 按发起时间倒序，带勋章名与志愿者姓名
     */
    public List<MedalGrantVO> listGrants(Integer status, Long volunteerId) {
        LambdaQueryWrapper<HonorMedalGrant> qw = Wrappers.<HonorMedalGrant>lambdaQuery()
                .eq(status != null, HonorMedalGrant::getStatus, status)
                .eq(volunteerId != null, HonorMedalGrant::getVolunteerId, volunteerId)
                .orderByDesc(HonorMedalGrant::getApplyTime)
                .orderByDesc(HonorMedalGrant::getId);
        List<HonorMedalGrant> grants = grantMapper.selectList(qw);
        if (grants.isEmpty()) {
            return List.of();
        }
        // 批量换名，避免逐行查库（N+1）
        Set<Long> volunteerIds = new LinkedHashSet<>();
        Set<Long> medalIds = new LinkedHashSet<>();
        for (HonorMedalGrant g : grants) {
            volunteerIds.add(g.getVolunteerId());
            medalIds.add(g.getMedalId());
        }
        Map<Long, String> names = volunteerQueryService.listNamesByIds(volunteerIds);
        Map<Long, HonorMedal> medals = new HashMap<>();
        for (HonorMedal m : medalService.listByIds(medalIds)) {
            medals.put(m.getId(), m);
        }

        List<MedalGrantVO> vos = new ArrayList<>(grants.size());
        for (HonorMedalGrant g : grants) {
            MedalGrantVO vo = new MedalGrantVO();
            vo.setId(g.getId());
            vo.setMedalId(g.getMedalId());
            HonorMedal m = medals.get(g.getMedalId());
            vo.setMedalName(m == null ? null : m.getName());
            vo.setIconUrl(m == null ? null : m.getIconUrl());
            vo.setVolunteerId(g.getVolunteerId());
            vo.setVolunteerName(names.get(g.getVolunteerId()));
            vo.setGrantType(g.getGrantType());
            vo.setRewardPoints(g.getRewardPoints());
            vo.setReason(g.getReason());
            vo.setStatus(g.getStatus());
            vo.setStatusLabel(MedalGrantStatus.labelOf(g.getStatus()));
            vo.setRejectReason(g.getRejectReason());
            vo.setApplyBy(g.getApplyBy());
            vo.setApplyTime(g.getApplyTime());
            vo.setReviewBy(g.getReviewBy());
            vo.setReviewTime(g.getReviewTime());
            vos.add(vo);
        }
        return vos;
    }

    /**
     * 志愿者端：全部已启用勋章 <b>∪ 本人已获得的勋章</b> + 我是否已获得 + 获取进度。
     *
     * <p><b>只统计已生效的发放</b>——待审/驳回的不该让志愿者看到，否则审核形同虚设。</p>
     *
     * <p><b>为什么要并上「本人已获得的」而不是只列已启用的</b>：勋章样式停用后，
     * 已经生效的发放<b>按设计不受影响</b>（{@code MedalStatus} 与接口文档都是这么写的）。
     * 若只遍历已启用定义，协会一停用样式，志愿者手上那枚勋章就会从「我的勋章」里凭空消失，
     * 与承诺相反。停用的语义是「不再发新的」，不是「收回已发的」。</p>
     *
     * <p><b>展示的一律是「最后一次通过审核的样式」</b>（V29 快照），不是当前定义。
     * 否则管理员改一枚已发出去的勋章、退回待审核之后，已获得者会立刻看到未过审的名称与图标；
     * 该版本即使随后被驳回，驳回稿也会一直挂在他的「我的勋章」里。</p>
     *
     * <p>进度对「有阈值」的条件类型即时计算，数据源与积分中心/排行榜同口径：
     * 时长 / 次数走 {@link ActivityRankingQueryService}（<b>只认已发布/已结束活动上的真实签到</b>，
     * 不是服务记录那份不筛活动状态的粗口径），积分取<b>累计获得</b>（非余额，
     * 花掉积分不该让人丢掉已挣到的进度）。手动授予类没有进度，字段留 null。</p>
     *
     * @param volunteerId 志愿者 id
     * @return 按 sort、id 正序
     */
    public List<MyMedalVO> myMedals(Long volunteerId) {
        Map<Long, HonorMedalGrant> owned = new HashMap<>();
        for (HonorMedalGrant g : grantMapper.selectList(Wrappers.<HonorMedalGrant>lambdaQuery()
                .eq(HonorMedalGrant::getVolunteerId, volunteerId)
                .eq(HonorMedalGrant::getStatus, MedalGrantStatus.EFFECTIVE))) {
            owned.put(g.getMedalId(), g);
        }
        /* 一律换成「最后过审版本」再展示。已启用行的快照与当前值本来就相同（任何修改都会把它
           退回待审核），真正有差别的是 unionOwned 补进来的那些非启用行——它们的当前值可能是
           管理员刚改、尚未过审甚至已被驳回的内容。见 V29 迁移注释。 */
        List<HonorMedal> visible = new ArrayList<>();
        for (HonorMedal m : unionOwned(medalService.listEnabled(), owned.keySet())) {
            visible.add(medalService.approvedView(m));
        }

        boolean needProgress = visible.stream().anyMatch(m -> MedalConditionType.hasProgress(m.getConditionType()));
        Long minutes = null;
        Long activities = null;
        Long earned = null;
        if (needProgress) {
            minutes = rankingQueryService.totalServiceMinutes(volunteerId);
            activities = rankingQueryService.totalAttendanceCount(volunteerId);
            Integer totalEarned = pointService.summary(volunteerId).getTotalEarned();
            earned = totalEarned == null ? 0L : totalEarned.longValue();
        }

        List<MyMedalVO> vos = new ArrayList<>(visible.size());
        for (HonorMedal medal : visible) {
            MyMedalVO vo = new MyMedalVO();
            vo.setMedalId(medal.getId());
            vo.setName(medal.getName());
            vo.setIconUrl(medal.getIconUrl());
            vo.setDescription(medal.getDescription());
            vo.setConditionType(medal.getConditionType());
            vo.setConditionTypeLabel(MedalConditionType.labelOf(medal.getConditionType()));
            vo.setConditionThreshold(medal.getConditionThreshold());
            vo.setRewardPoints(medal.getRewardPoints());
            HonorMedalGrant g = owned.get(medal.getId());
            vo.setOwned(g != null);
            vo.setGrantTime(g == null ? null : g.getReviewTime());
            if (MedalConditionType.hasProgress(medal.getConditionType())) {
                long current = switch (medal.getConditionType()) {
                    case MedalConditionType.SERVICE_MINUTES -> minutes == null ? 0L : minutes;
                    case MedalConditionType.ACTIVITY_COUNT -> activities == null ? 0L : activities;
                    default -> earned == null ? 0L : earned;
                };
                vo.setCurrentValue(current);
                vo.setProgressPercent(percent(current, medal.getConditionThreshold()));
            }
            vos.add(vo);
        }
        return vos;
    }

    // ---------- helpers ----------

    /**
     * 已启用定义 ∪ 本人已获得所引用的定义，按 sort、id 正序。
     *
     * <p>补进来的多半是<b>已停用</b>的样式（停用不收回已发的勋章）；理论上也可能是被逻辑删除的，
     * 但 {@code MedalService.delete} 会拦下「有生效发放」的删除，那种行不该存在，
     * 真出现了（历史脏数据）也只是查不到、跳过而已。</p>
     */
    private List<HonorMedal> unionOwned(List<HonorMedal> enabled, java.util.Collection<Long> ownedMedalIds) {
        Set<Long> present = new LinkedHashSet<>();
        for (HonorMedal m : enabled) {
            present.add(m.getId());
        }
        List<Long> missing = new ArrayList<>();
        for (Long id : ownedMedalIds) {
            if (!present.contains(id)) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return enabled;
        }
        List<HonorMedal> merged = new ArrayList<>(enabled);
        merged.addAll(medalService.listByIds(missing));
        merged.sort(Comparator
                .comparing((HonorMedal m) -> m.getSort() == null ? 0 : m.getSort())
                .thenComparing(HonorMedal::getId));
        return merged;
    }

    private HonorMedalGrant requireGrant(Long grantId) {
        HonorMedalGrant grant = grantId == null ? null : grantMapper.selectById(grantId);
        if (grant == null) {
            throw new BusinessException("发放记录不存在");
        }
        return grant;
    }

    private void requireRegisteredVolunteer(Long volunteerId) {
        VolunteerFlagInfoView info = volunteerQueryService.getFlagInfo(volunteerId);
        if (info == null) {
            throw new BusinessException("志愿者不存在");
        }
        if (!info.registered()) {
            throw new BusinessException("该志愿者尚未实名注册，不能授予勋章");
        }
        if (!volunteerQueryService.isActive(volunteerId)) {
            throw new BusinessException("该志愿者账号非正常状态，不能授予勋章");
        }
    }

    /**
     * 审核生效那一刻复核志愿者资格，<b>当前读</b>。
     *
     * <p>与勋章那一侧对称：{@code apply} 校验的是发起那一刻的资格，而勋章生效 + 积分入账
     * 发生在审核那一刻，中间可能隔着好几天。期间志愿者被禁用、注销或删除，
     * 快照读（{@code getFlagInfo}/{@code isActive}）看不到——本方法已在事务中，
     * 读视图在取发放记录那条查询时就已定死。</p>
     *
     * <p>与勋章不同的是：<b>驳回它没有意义</b>。志愿者被禁用是 auth 域的状态，
     * 恢复之后这条待审发放仍然应当可以通过，所以这里只是拒绝本次通过并说明原因，
     * 不自动改发放记录状态——留给管理员决定是等恢复还是驳回。</p>
     */
    private void requireGrantableVolunteerForShare(Long volunteerId) {
        VolunteerGrantEligibilityView v = volunteerQueryService.getGrantEligibilityForShare(volunteerId);
        if (v == null) {
            throw new BusinessException("该志愿者已不存在（可能已被删除），不能通过发放");
        }
        if (!v.registered()) {
            throw new BusinessException("该志愿者当前未实名注册，不能通过发放");
        }
        if (!v.active()) {
            throw new BusinessException("该志愿者账号当前为「"
                    + (UserStatus.BANNED.equals(v.status()) ? "已禁用" : "已注销")
                    + "」，不能通过发放；请待其恢复正常后再审，或直接驳回");
        }
    }

    /** 进度百分比，封顶 100——超额完成显示 120% 只会让人困惑。 */
    private Integer percent(long current, Long threshold) {
        if (threshold == null || threshold <= 0) {
            return null;
        }
        long p = current * 100 / threshold;
        return (int) Math.min(100, Math.max(0, p));
    }

    private String truncate(String reason) {
        if (!StringUtils.hasText(reason)) {
            return null;
        }
        String trimmed = reason.trim();
        return trimmed.length() <= 512 ? trimmed : trimmed.substring(0, 512);
    }
}
