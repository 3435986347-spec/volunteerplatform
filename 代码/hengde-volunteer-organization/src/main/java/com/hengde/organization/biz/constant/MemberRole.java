package com.hengde.organization.biz.constant;

/**
 * 小组成员角色码：0普通成员 / 1组长 / 2管理员。
 *
 * @author hengde
 */
public final class MemberRole {

    private MemberRole() {
    }

    /** 普通成员 */
    public static final int MEMBER = 0;
    /** 组长 */
    public static final int LEADER = 1;
    /** 管理员（承担日常审批/移除，与组长协同；不参与组长转移） */
    public static final int ADMIN = 2;
}
