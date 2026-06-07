package com.hengde.activity.constant;

/**
 * 活动报名状态码：0待审核 / 1已通过 / 2已拒绝 / 3已取消。
 *
 * <p>跨多个 service（报名、考勤名单、负责人指派等）共用的单一来源，避免各处重复定义魔法数导致漂移。</p>
 *
 * @author hengde
 */
public final class EnrollmentStatus {

    private EnrollmentStatus() {
    }

    /** 待审核 */
    public static final int PENDING = 0;
    /** 已通过 */
    public static final int APPROVED = 1;
    /** 已拒绝 */
    public static final int REJECTED = 2;
    /** 已取消 */
    public static final int CANCELLED = 3;
}
