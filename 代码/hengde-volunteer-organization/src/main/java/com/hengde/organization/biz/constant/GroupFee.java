package com.hengde.organization.biz.constant;

/**
 * 发起小组的积分费用（原型 P23「发起小组（-50积分）」，需方 2026-10-23 拍板补上）。
 *
 * @author hengde
 */
public final class GroupFee {

    private GroupFee() {
    }

    /** 发起小组扣的积分；建组被驳回时如数退回，后台导入的小组不扣 */
    public static final int CREATE_COST = 50;
}
