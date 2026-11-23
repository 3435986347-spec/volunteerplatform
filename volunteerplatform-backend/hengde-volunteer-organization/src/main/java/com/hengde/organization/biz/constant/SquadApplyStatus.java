package com.hengde.organization.biz.constant;

/**
 * 分队加入申请状态码：0待审核 / 1已通过 / 2已拒绝。
 *
 * @author hengde
 */
public final class SquadApplyStatus {

    private SquadApplyStatus() {
    }

    /** 待审核 */
    public static final int PENDING = 0;
    /** 已通过 */
    public static final int APPROVED = 1;
    /** 已拒绝 */
    public static final int REJECTED = 2;
}
