package com.hengde.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 活动报名记录（志愿者报名某活动的某时间段）。
 *
 * <p>防重复不靠 DB 唯一键，由 Redisson 锁 + 应用层校验「无活跃报名(status 0/1)」实现，
 * 兼容取消后再报名并保留历史。取消报名置 {@code status=3}（不删行）；
 * {@code isDeleted}（继承自 BaseEntity）仅供后台「删除报名记录」用，与志愿者取消无关。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("activity_enrollment")
public class ActivityEnrollment extends BaseEntity {

    /** 活动 activity.id */
    private Long activityId;

    /** 时间段 activity_slot.id */
    private Long slotId;

    /** 志愿者 volunteer.id */
    private Long volunteerId;

    /** 状态 0待审核/1已通过/2已拒绝/3已取消 */
    private Integer status;

    /** 报名时间 */
    private LocalDateTime enrollTime;

    /** 拒绝原因 */
    private String rejectReason;

    /** 代报名人 volunteer.id（本版搁置，预留） */
    private Long proxyByVolunteerId;

    /** 审核管理员 admin_user.id */
    private Long auditBy;

    /** 审核时间 */
    private LocalDateTime auditTime;
}
