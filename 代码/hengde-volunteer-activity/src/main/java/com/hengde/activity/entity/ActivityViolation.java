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

    // ---------- 组织部审核（V32，第 5 批）----------

    /** 待审核 */
    public static final int REVIEW_PENDING = 0;
    /** 审核通过——通过后才对志愿者可见 */
    public static final int REVIEW_APPROVED = 1;
    /** 已驳回 */
    public static final int REVIEW_REJECTED = 2;

    /**
     * 组织部审核状态 0待审核/1已通过/2已驳回。
     *
     * <p><b>需求出处</b>：xlsx Row 41 F「各类违规记录和奖励均需<b>组织部同学审核才可显示</b>」；
     * Row 59 后台首页待办里单列了「<b>活动违规审核</b>」这一项，即违规记录本身要过一道审。</p>
     *
     * <p>现场记录是负责人的<b>工作底稿</b>：负责人在活动现场凭观察点几下，
     * 未经组织部核实就直接呈现给志愿者，等于把一面之词当成定论。</p>
     */
    private Integer reviewStatus;

    /** 审核人 admin_user.id */
    private Long reviewedBy;

    /** 审核时间 */
    private LocalDateTime reviewTime;

    /** 驳回原因 */
    private String rejectReason;
}
