package com.hengde.activity.constant;

import java.time.LocalDateTime;

/**
 * 志愿者端「活动展示状态」——按当前时间 + 报名开放/截止 + 现场运行状态实时派生，
 * 区别于数据库持久化的发布态 {@link ActivityStatus}（草稿/已发布/已结束/已取消，不随时间流转）。
 *
 * <p>列表/详情只返回「已发布(status=1)」的活动，其数据库 status 恒为 1，若直接拿它当展示状态，
 * 小程序卡片会永远显示「报名中」（哪怕早过截止）。这里据时间窗口派生出 5 态供前端徽标显示，
 * 编码与小程序 {@code data-service.js} 的映射一致（0未开放/1报名中/2报名截止/3活动中/4已结束）。</p>
 *
 * @author hengde
 */
public final class ActivityDisplayStatus {

    private ActivityDisplayStatus() {
    }

    /** 未开放（志愿者报名开放时间未到） */
    public static final int NOT_OPEN = 0;
    /** 报名中 */
    public static final int ENROLLING = 1;
    /** 报名截止（已过报名截止时间，活动尚未开始） */
    public static final int ENROLL_CLOSED = 2;
    /** 活动中（处于活动起止时间内，或现场已开始） */
    public static final int IN_PROGRESS = 3;
    /** 已结束 */
    public static final int ENDED = 4;

    /**
     * 派生展示状态。优先级：已结束 &gt; 活动中 &gt; 未开放 &gt; 报名截止 &gt; 报名中。
     * 入参均可为空，缺失的维度自动跳过（如未配报名开放时间则不会判为「未开放」）。
     *
     * @param now                当前时间
     * @param startTime          活动开始时间
     * @param endTime            活动结束时间
     * @param enrollOpenVolunteer 志愿者报名开放时间
     * @param enrollDeadline     报名截止时间
     * @param runStatus          现场运行状态（{@link RunStatus}，可空）
     * @return 展示状态码（见本类常量）
     */
    public static int derive(LocalDateTime now, LocalDateTime startTime, LocalDateTime endTime,
                             LocalDateTime enrollOpenVolunteer, LocalDateTime enrollDeadline,
                             Integer runStatus) {
        // 已结束：现场标记已结束，或已过活动结束时间
        if ((runStatus != null && runStatus == RunStatus.ENDED)
                || (endTime != null && now.isAfter(endTime))) {
            return ENDED;
        }
        // 活动中：现场标记进行中，或落在活动起止时间内
        if ((runStatus != null && runStatus == RunStatus.RUNNING)
                || (startTime != null && !now.isBefore(startTime)
                && (endTime == null || !now.isAfter(endTime)))) {
            return IN_PROGRESS;
        }
        // 活动开始前——报名阶段
        if (enrollOpenVolunteer != null && now.isBefore(enrollOpenVolunteer)) {
            return NOT_OPEN;
        }
        if (enrollDeadline != null && now.isAfter(enrollDeadline)) {
            return ENROLL_CLOSED;
        }
        return ENROLLING;
    }
}
