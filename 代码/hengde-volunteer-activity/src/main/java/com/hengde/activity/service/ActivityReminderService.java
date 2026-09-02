package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.config.ActivityProperties;
import com.hengde.activity.constant.ActivityStatus;
import com.hengde.activity.constant.EnrollmentStatus;
import com.hengde.activity.dao.ActivityEnrollmentMapper;
import com.hengde.activity.dao.ActivityMapper;
import com.hengde.activity.dao.ActivitySlotMapper;
import com.hengde.activity.entity.Activity;
import com.hengde.activity.entity.ActivityEnrollment;
import com.hengde.activity.entity.ActivitySlot;
import com.hengde.auth.service.SmsNotifyService;
import com.hengde.common.sms.SmsNotifyTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「活动即将开始」提醒（模板 {@code activity-start-reminder}）。
 *
 * <p>这是 20 条通知模板里<b>唯一没有对应用户动作</b>的一条——「活动快开始了」不是谁点出来的，
 * 只能定时扫。触发层在 {@code ActivityStartReminderJob}，本类只管扫什么、发给谁。</p>
 *
 * <h3>按场次，不按活动</h3>
 * <p>场次是参与的最小单元。一个活动上午场、下午场各一批人，提醒必须各发各的，
 * 否则下午场的人要么收不到、要么在前一天收到写着上午时间的短信。</p>
 *
 * <h3>宁可漏发，不可重发</h3>
 * <p>顺序是<b>先 CAS 置「已提醒」，再发短信</b>。中间崩掉会漏掉这一场（至多一次语义），
 * 反过来「先发后记」在崩溃时会让下一轮重发一遍——短信要花钱，而且同一条提醒连发两遍，
 * 收信人多半会以为活动改期了。两害相权，漏发那一场还能由人工补，重发收不回。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class ActivityReminderService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ActivitySlotMapper slotMapper;
    private ActivityMapper activityMapper;
    private ActivityEnrollmentMapper enrollmentMapper;
    private SmsNotifyService smsNotifyService;
    private ActivityProperties activityProperties;

    @Autowired
    public void setSlotMapper(ActivitySlotMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    @Autowired
    public void setActivityMapper(ActivityMapper activityMapper) {
        this.activityMapper = activityMapper;
    }

    @Autowired
    public void setEnrollmentMapper(ActivityEnrollmentMapper enrollmentMapper) {
        this.enrollmentMapper = enrollmentMapper;
    }

    @Autowired
    public void setSmsNotifyService(SmsNotifyService smsNotifyService) {
        this.smsNotifyService = smsNotifyService;
    }

    @Autowired
    public void setActivityProperties(ActivityProperties activityProperties) {
        this.activityProperties = activityProperties;
    }

    /**
     * 扫出即将开始、尚未提醒过的场次，各发一轮提醒。
     *
     * <p>幂等：已提醒过的场次不会被再次扫到，因此每小时重跑无副作用，漏跑一轮下一轮补上
     * （只要那一场还没开始）。</p>
     *
     * @return 本次真正发出提醒的场次数
     */
    public int sendDueReminders() {
        ActivityProperties.Reminder cfg = activityProperties.getReminder();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime until = now.plusHours(cfg.getLeadHours());

        // 只取还没开始的：已经开始的场次再提醒「请准时签到」毫无意义，
        // 而下界取 now 也顺带让「服务停了两天」这种情况不会把过去的场次翻出来群发。
        List<ActivitySlot> due = slotMapper.selectList(Wrappers.<ActivitySlot>lambdaQuery()
                .isNull(ActivitySlot::getReminderSentTime)
                .isNotNull(ActivitySlot::getStartTime)
                .ge(ActivitySlot::getStartTime, now)
                .le(ActivitySlot::getStartTime, until)
                .orderByAsc(ActivitySlot::getStartTime)
                // 钳下限：配置来的值没有注入风险，但 0 会让任务静默什么都不发、
                // 负值直接拼出语法错误的 SQL——两种都只在日志里留一行，没人会去看
                .last("limit " + Math.max(1, cfg.getBatchLimit())));
        if (due.isEmpty()) {
            return 0;
        }

        int reminded = 0;
        for (ActivitySlot slot : due) {
            try {
                if (remindOne(slot, now)) {
                    reminded++;
                }
            } catch (Exception ex) {
                // 逐场次兜住：一场的坏数据不能让后面的人都收不到提醒
                log.error("[SMS-NOTIFY] 活动开始提醒失败 slotId={}", slot.getId(), ex);
            }
        }
        return reminded;
    }

    /**
     * 处理一个场次。
     *
     * <p><b>公开而非私有</b>：它是「提醒一个场次」这件事的完整单元，语义自洽；
     * 而用例需要拿一个「已经被别人标记过」的过期 {@link ActivitySlot} 对象直接调它，
     * 以确定性地构造「第二个扫描方在 select 之后才执行 update」这一时序——
     * 那正是下面 ② 号防线要挡的东西，靠多线程加 sleep 撞不可靠。
     * 生产路径只应经 {@link #sendDueReminders()} 进入。</p>
     *
     * @return 是否真的发了提醒（无人可通知、活动未上线、CAS 落空都返回 false）
     */
    public boolean remindOne(ActivitySlot slot, LocalDateTime now) {
        Activity activity = activityMapper.selectById(slot.getActivityId());
        // 【不置「已提醒」直接跳过】草稿/待审核的活动可能在开始前才通过审核，
        // 那时它仍该发提醒；此刻就把标记打上，等于永久剥夺了这一场的提醒。
        // 代价只是这些场次在窗口内被重复扫到，而窗口是有界的，成本可以忽略。
        if (activity == null || !Integer.valueOf(ActivityStatus.PUBLISHED).equals(activity.getStatus())) {
            return false;
        }

        // 【只提醒已通过审核的】待审核的人还不知道自己去不去得成，
        // 让他「携带证件准时签到」是错的指示。需审核的活动里，管理员不批就不该有人到场。
        List<ActivityEnrollment> rowsEnrolled = enrollmentMapper.selectList(
                Wrappers.<ActivityEnrollment>lambdaQuery()
                        .select(ActivityEnrollment::getVolunteerId)
                        .eq(ActivityEnrollment::getSlotId, slot.getId())
                        .eq(ActivityEnrollment::getStatus, EnrollmentStatus.APPROVED));
        Set<Long> volunteerIds = rowsEnrolled.stream()
                .map(ActivityEnrollment::getVolunteerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // 【查收件人必须在打标记之前】没人可通知就直接走，<b>不打标记</b>——
        // 与上面「活动没上线不打标记」是同一个形状：`need_audit=1` 的活动在 T-24h 扫到时
        // 报名可能全是待审核，管理员 T-20h 才批。若此刻把标记打上，那 30 个人永远收不到提醒，
        // 因为扫描条件 `reminder_sent_time IS NULL` 已经把这一场排除在外了。
        // 本项目报名/审批默认可以持续到活动结束，24 小时内批人是常态，不是边角情形。
        if (volunteerIds.isEmpty()) {
            return false;
        }

        // 【两道防线，别混为一谈】
        // ① 上面的扫描条件 reminder_sent_time IS NULL 已经让「已提醒过的场次」不会被再次取出——
        //    单实例下重复发送就是被它挡住的。
        // ② 这里的 CAS 防的是【两个实例同时扫到同一场】：只有一方的 affected 会是 1。
        //    两条都有用例（后者由 remindOne 直接喂一个「过期的 slot 对象」确定性构造，
        //    等价于第二个扫描方在 select 之后才执行 update，不靠线程也不靠 sleep）。
        //
        // 【顺序：先标记后发】宁可漏发不可重发——中间崩掉会漏掉这一场（至多一次语义），
        // 反过来「先发后记」在崩溃时会让下一轮重发一遍。短信要花钱，
        // 而且同一条提醒连发两遍，收信人多半会以为活动改期了。
        int rows = slotMapper.update(null, Wrappers.<ActivitySlot>lambdaUpdate()
                .set(ActivitySlot::getReminderSentTime, now)
                // wrapper 更新绕过 MetaObjectHandler 的自动填充，故显式写 update_time
                // （与 EnrollmentAdminService 的 CAS 更新同一处理）
                .set(ActivitySlot::getUpdateTime, now)
                .eq(ActivitySlot::getId, slot.getId())
                .isNull(ActivitySlot::getReminderSentTime));
        if (rows != 1) {
            return false;
        }

        smsNotifyService.notifyVolunteers(volunteerIds, SmsNotifyTemplate.ACTIVITY_START_REMINDER,
                SmsNotifyTemplate.ACTIVITY_START_REMINDER.params(
                        activity.getTitle(),
                        TIME_FMT.format(slot.getStartTime()),
                        activity.getLocation()));
        return true;
    }
}
