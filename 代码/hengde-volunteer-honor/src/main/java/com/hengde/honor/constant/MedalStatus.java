package com.hengde.honor.constant;

/**
 * 勋章定义（样式）的状态机。
 *
 * <pre>
 *   0草稿 ──提交──▶ 1待审核 ──通过──▶ 2已启用 ──停用──▶ 4已停用
 *                      │
 *                      └──驳回──▶ 3已驳回 ──改后重交──▶ 1待审核
 * </pre>
 *
 * <p><b>只有「已启用」的勋章可以发放</b>——这正是「样式须先过审」的落点。
 * 停用不影响已经生效的发放记录：志愿者已经拿到的勋章不该因为协会下架了这个样式而消失。</p>
 *
 * @author hengde
 */
public final class MedalStatus {

    private MedalStatus() {
    }

    /** 草稿：可自由编辑，尚未提交审核 */
    public static final int DRAFT = 0;
    /** 待审核 */
    public static final int PENDING = 1;
    /** 已启用：审核通过，可用于发放 */
    public static final int ENABLED = 2;
    /** 已驳回：可修改后重新提交 */
    public static final int REJECTED = 3;
    /** 已停用：不可再发放，已生效的发放记录不受影响 */
    public static final int DISABLED = 4;

    /**
     * 状态中文名。
     *
     * @param status 状态码
     * @return 中文名；未知码返回「未知」
     */
    public static String labelOf(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case DRAFT -> "草稿";
            case PENDING -> "待审核";
            case ENABLED -> "已启用";
            case REJECTED -> "已驳回";
            case DISABLED -> "已停用";
            default -> "未知";
        };
    }
}
