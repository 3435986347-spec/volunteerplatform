package com.hengde.trade.constant;

/**
 * trade 域权限点编码（与 V55 迁移 {@code permission} 表预置数据一致）。
 *
 * <p><b>常量放在 trade、注解写在 api</b>：trade 模块不放 controller（V3规划 D1 的规矩 1），
 * 但权限码是本域的语义，放这里才不会在 api 里散落字符串。api 的控制器
 * {@code @SaCheckPermission(value = PermissionCode.TRADE_ORDER, type = "admin")} 引用它。</p>
 *
 * @author hengde
 */
public final class PermissionCode {

    private PermissionCode() {
    }

    /** 交易单查看 / 主动查单 / 关单 / 对账（只读与「问一下微信」这类不动钱的动作）。 */
    public static final String TRADE_ORDER = "trade:order";

    /**
     * 退款。
     *
     * <p>与 {@link #TRADE_ORDER} 分开，沿用 V24「查看与调整分开」的口径：
     * 退款是<b>把钱退出去</b>，与看一眼单子不是一回事。</p>
     *
     * <p><b>拆分不等于「不得自审」</b>：同一账号可以兼有两点，超管更是通配全有——
     * 要强制双人复核得另加创建人字段校验，本批没有做（与勋章、奖惩、商品审核同一状态）。</p>
     */
    public static final String TRADE_REFUND = "trade:refund";
}
