package com.hengde.organization.biz.constant;

/**
 * 小组状态码：0待审核 / 1正常 / 2已拒绝 / 3已解散。
 *
 * @author hengde
 */
public final class GroupStatus {

    private GroupStatus() {
    }

    /** 待审核（建组申请） */
    public static final int PENDING = 0;
    /** 正常 */
    public static final int ACTIVE = 1;
    /** 已拒绝 */
    public static final int REJECTED = 2;
    /** 已解散 */
    public static final int DISSOLVED = 3;
}
