package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.constant.SecretaryStatus;
import com.hengde.activity.dao.ActivityAttendanceMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityAttendance;
import com.hengde.activity.entity.ActivitySlot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 证书所需的活动域<b>只读窄接口</b>。
 *
 * <p><b>为什么要有这一层</b>：honor 域此前直接注入了 {@code ActivityMapper}/{@code ActivitySlotMapper}/
 * {@code ActivityAttendanceMapper} 三个 Mapper，等于跨模块捅进了别人的数据层——
 * 项目约定是「领域间通过对方 service 接口调用」（见 {@code CLAUDE.md}），
 * 已有的 {@link ActivityRankingQueryService} 正是同一条约定的产物。
 * 直连 Mapper 的代价很实：活动域改表、加逻辑删除条件、调整「什么算有效考勤」，
 * honor 不会跟着变，两边口径会静默漂开。</p>
 *
 * <p>本服务把「谁该拿到证书」这条活动域语义收在自己内部，出参是收敛视图，不外泄实体。</p>
 *
 * @author hengde
 */
@Service
public class ActivityCertificateQueryService {

    /**
     * 一张证书的活动侧事实。
     *
     * @param attendanceId     考勤行 id
     * @param activityId       活动 id
     * @param slotId           场次 id
     * @param volunteerId      志愿者 id
     * @param activityTitle    活动名称
     * @param slotProjectName  场次（岗位）名称
     * @param slotStartTime    场次开始
     * @param slotEndTime      场次结束
     * @param serviceMinutes   服务时长（分钟）
     */
    public record CertificateSubject(Long attendanceId, Long activityId, Long slotId, Long volunteerId,
                                     String activityTitle, String slotProjectName,
                                     LocalDateTime slotStartTime, LocalDateTime slotEndTime,
                                     Integer serviceMinutes) {
    }

    /** 活动 + 场次的展示信息，供证书列表批量 join。 */
    public record SlotDisplay(Long activityId, String activityTitle,
                              Long slotId, String slotProjectName,
                              LocalDateTime slotStartTime, LocalDateTime slotEndTime) {
    }

    private ActivityMapper activityMapper;
    private ActivitySlotMapper slotMapper;
    private ActivityAttendanceMapper attendanceMapper;

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setSlotMapper(ActivitySlotMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    @Autowired
    public void setAttendanceMapper(ActivityAttendanceMapper attendanceMapper) {
        this.attendanceMapper = attendanceMapper;
    }

    /**
     * 该志愿者在这一场次是否<b>确实参加且已由秘书部确认</b>——发证的资格判据。
     *
     * <p>「已确认」是本项目里「这次服务作数」的统一口径（时长榜、积分发放都以它为界）。
     * 证书跟着同一条线，不另立标准。</p>
     */
    public boolean hasConfirmedAttendance(Long activityId, Long slotId, Long volunteerId) {
        if (activityId == null || slotId == null || volunteerId == null) {
            return false;
        }
        // 【只查存在性，不要走 findSubject】后者为了拼展示字段会再查活动与场次两张表，
        // 而这里只需要一个是非。补发路径上每张证书都过这道闸（且 createForSlot 是所有
        // 创建入口的必经之地），把 3 次读省成 1 次索引点查是白捡的。
        return attendanceMapper.selectCount(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, activityId)
                .eq(ActivityAttendance::getSlotId, slotId)
                .eq(ActivityAttendance::getVolunteerId, volunteerId)
                .eq(ActivityAttendance::getSecretaryStatus, SecretaryStatus.CONFIRMED)) > 0;
    }

    /** 取发证所需的活动侧事实；不存在或未确认返回 {@code null}。 */
    public CertificateSubject findSubject(Long activityId, Long slotId, Long volunteerId) {
        if (activityId == null || slotId == null || volunteerId == null) {
            return null;
        }
        ActivityAttendance att = attendanceMapper.selectOne(Wrappers.<ActivityAttendance>lambdaQuery()
                .eq(ActivityAttendance::getActivityId, activityId)
                .eq(ActivityAttendance::getSlotId, slotId)
                .eq(ActivityAttendance::getVolunteerId, volunteerId)
                .eq(ActivityAttendance::getSecretaryStatus, SecretaryStatus.CONFIRMED)
                .last("limit 1"));
        return att == null ? null : toSubject(att);
    }

    /**
     * 仅归属信息的轻量视图，供补偿扫描做差集用。
     *
     * <p>与 {@link CertificateSubject} 的区别：<b>不带活动名/岗位名/时长</b>。
     * 补偿扫描只需要知道「谁在哪一场」，而那些展示字段每行都要额外查两次库
     * （活动 + 场次），扫全表时就是 N+1。真要渲染时再走 {@link #findSubject} 取一次即可。</p>
     */
    public record AttendanceRef(Long attendanceId, Long activityId, Long slotId, Long volunteerId) {
    }

    /**
     * 列出已确认的考勤，供证书补偿扫描使用。<b>必须给出至少一个边界</b>。
     *
     * <p><b>为什么不能两个边界都不给</b>：不加限制会从库里第一条已确认考勤开始扫，
     * 每次跑都是一次全表扫描，而定时补偿要解决的是「刚刚那条事件丢了」，本就只需要覆盖近期。</p>
     *
     * <p><b>历史活动不补发</b>（协会 2026-08-11：只对系统上线后开展的活动自动发证；
     * 且库里现存的历史活动<b>全部是测试数据</b>，《协会待确认清单》「1-追」已据此关闭）。
     * 边界因此不再承担「别替协会答题」这个职责，但它仍然要在——理由换成了上一段那条。</p>
     *
     * <p>两个边界的分工：定时任务传 {@code confirmedAfter}（回看窗口）；
     * 管理员显式补发时传 {@code activityId}，把范围锁在那一个活动上——
     * 后者是回看窗口之外唯一的人工救济（事件丢失 + 停机超过 72 小时叠加时用），
     * 范围由人指定而不是由 cron 决定。</p>
     *
     * <p>按 id 递增分页（不用 offset）：补偿任务会反复扫，offset 分页在并发写入下会漏行。</p>
     *
     * @param confirmedAfter    只看这个时刻<b>之后</b>被秘书部确认的考勤；可为 null（此时必须给 activityId）
     * @param activityId        只看这个活动下的考勤；可为 null（此时必须给 confirmedAfter）
     * @param afterAttendanceId 上一批的最大考勤 id，首次传 0
     * @param limit             本批条数
     */
    public List<AttendanceRef> listConfirmedAfter(LocalDateTime confirmedAfter, Long activityId,
                                                  long afterAttendanceId, int limit) {
        // fail-closed：两个边界都没有就返回空，而不是当成「扫全库」
        if ((confirmedAfter == null && activityId == null) || limit <= 0) {
            return List.of();
        }
        return attendanceMapper.selectList(Wrappers.<ActivityAttendance>lambdaQuery()
                        .select(ActivityAttendance::getId, ActivityAttendance::getActivityId,
                                ActivityAttendance::getSlotId, ActivityAttendance::getVolunteerId)
                        .eq(ActivityAttendance::getSecretaryStatus, SecretaryStatus.CONFIRMED)
                        .ge(confirmedAfter != null, ActivityAttendance::getSecretaryTime, confirmedAfter)
                        .eq(activityId != null, ActivityAttendance::getActivityId, activityId)
                        .gt(ActivityAttendance::getId, afterAttendanceId)
                        .orderByAsc(ActivityAttendance::getId)
                        .last("limit " + limit))
                .stream()
                .map(a -> new AttendanceRef(a.getId(), a.getActivityId(), a.getSlotId(), a.getVolunteerId()))
                .toList();
    }

    /** 批量取场次展示信息，供证书列表 join（key = slotId）。 */
    public Map<Long, SlotDisplay> listSlotDisplays(Collection<Long> slotIds) {
        if (slotIds == null || slotIds.isEmpty()) {
            return Map.of();
        }
        List<ActivitySlot> slots = slotMapper.selectBatchIds(slotIds);
        if (slots.isEmpty()) {
            return Map.of();
        }
        List<Long> activityIds = slots.stream().map(ActivitySlot::getActivityId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> titleById = new HashMap<>();
        if (!activityIds.isEmpty()) {
            for (Activity a : activityMapper.selectBatchIds(activityIds)) {
                titleById.put(a.getId(), a.getTitle());
            }
        }
        Map<Long, SlotDisplay> result = new HashMap<>();
        for (ActivitySlot s : slots) {
            result.put(s.getId(), new SlotDisplay(s.getActivityId(), titleById.get(s.getActivityId()),
                    s.getId(), s.getProjectName(), s.getStartTime(), s.getEndTime()));
        }
        return result;
    }

    private CertificateSubject toSubject(ActivityAttendance att) {
        Activity a = att.getActivityId() == null ? null : activityMapper.selectById(att.getActivityId());
        ActivitySlot s = att.getSlotId() == null ? null : slotMapper.selectById(att.getSlotId());
        return new CertificateSubject(att.getId(), att.getActivityId(), att.getSlotId(), att.getVolunteerId(),
                a == null ? null : a.getTitle(),
                s == null ? null : s.getProjectName(),
                s == null ? null : s.getStartTime(),
                s == null ? null : s.getEndTime(),
                att.getServiceMinutes());
    }
}
