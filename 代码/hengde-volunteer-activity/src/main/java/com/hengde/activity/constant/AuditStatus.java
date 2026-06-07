package com.hengde.activity.constant;

/**
 * 二次审核记录状态码：0待审核 / 1已通过 / 2已拒绝。
 *
 * <p>考勤/积分变更（{@code ActivityAttendanceChange}）与活动补录（{@code ActivityBackfill}）等
 * 「申请→部长审核」类记录共用的单一来源。</p>
 *
 * @author hengde
 */
public final class AuditStatus {

    private AuditStatus() {
    }

    /** 待审核 */
    public static final int PENDING = 0;
    /** 已通过 */
    public static final int APPROVED = 1;
    /** 已拒绝 */
    public static final int REJECTED = 2;
}
