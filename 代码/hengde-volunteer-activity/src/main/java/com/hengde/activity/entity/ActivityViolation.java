package com.hengde.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 活动违规记录（每活动每志愿者可多条）。
 *
 * <p>缺席由系统自动记一条（{@code violationType=5}）；其余由负责人手动记录。
 * 积分发放时据是否存在违规决定减半/不发（落在 {@link ActivityAttendance#getPointsFactor()}）。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("activity_violation")
public class ActivityViolation extends BaseEntity {

    /** 活动 activity.id */
    private Long activityId;

    /**
     * 场次 activity_slot.id（V30 新增）。
     *
     * <p>依据 xlsx Row 32「C 前端信息」：负责人管理页按「活动场次／活动时间段」组织，
     * 「是否到位」与「是否违规」都记在该场次的界面里。</p>
     */
    private Long slotId;

    /** 志愿者 volunteer.id */
    private Long volunteerId;

    /** 类型 1玩手机/2服装不合格/3早退/4长时间交头接耳/5缺席/0其他 */
    private Integer violationType;

    /** 违规说明 */
    private String description;

    /** 记录人（负责人 volunteer.id 或 admin_user.id） */
    private Long recordedBy;

    /** 记录时间 */
    private LocalDateTime recordedTime;
}
