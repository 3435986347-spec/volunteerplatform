package com.hengde.organization.biz.constant;

/**
 * 小组成员状态码：0待审核 / 1在册 / 2已拒绝 / 3已退出 / 4已移除。
 *
 * <p>{@code GroupService} 与跨模块只读的 {@code GroupQueryService} 共用的单一来源。</p>
 *
 * @author hengde
 */
public final class MemberStatus {

    private MemberStatus() {
    }

    /** 待审核加入 */
    public static final int PENDING = 0;
    /** 在册（正式成员） */
    public static final int ACTIVE = 1;
    /** 已拒绝 */
    public static final int REJECTED = 2;
    /** 已退出 */
    public static final int LEFT = 3;
    /** 已移除 */
    public static final int REMOVED = 4;
}
