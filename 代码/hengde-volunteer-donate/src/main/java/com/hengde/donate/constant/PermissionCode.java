package com.hengde.donate.constant;

/**
 * donate 域权限点编码（与 V41 迁移 {@code permission} 表预置数据一致）。
 *
 * <p>管理端接口用 {@code @SaCheckPermission(value=..., type="admin")} 引用；
 * 权限数据由 organization 的 StpInterface 提供，超管走 {@code *} 万能码放行。</p>
 *
 * <p><b>前缀按模块（{@code donate:}）不按聚合根</b>——现有 51 个点全是
 * {@code activity:}/{@code honor:}/{@code org:}/{@code pub:}/{@code user:}/{@code data:}，无一例外，
 * 而权限码进库之后还要分配给账号，改名的代价随时间涨。<b>表名才按聚合根概念分</b>
 * {@code mall_}/{@code donate_}：URL 与权限点按模块，表名按概念，两套规则各有各的理由
 * （V3规划·承重条款 2）。</p>
 *
 * <p>志愿者端 {@code /v/donate/**} <b>不挂权限点</b>——商品与兑换是给全体志愿者用的，只需登录。</p>
 *
 * @author hengde
 */
public final class PermissionCode {

    private PermissionCode() {
    }

    /**
     * 积分商品管理（增删改、排序隐藏、提交审核；图片走 {@code /a/files/upload?dir=goods}）。
     *
     * <p><b>兑换规则（Row 8 C 的文字 + 图片）读写也用这个点，刻意不新增。</b>
     * 拆权限要有「谁能改 A 但不能改 B」的现实需求，这里没有——能维护商品的人本来就该能维护
     * 兑换规则。{@code user:export} 那次拆开，是因为导出带走的是志愿者个人数据、
     * 敏感度与列表查看不同，不是同一回事。记在这里，免得下次有人凭「看着该拆」就拆。</p>
     */
    public static final String DONATE_GOODS = "donate:goods";

    /**
     * 积分商品<b>审核</b>。
     *
     * <p>与 {@link #DONATE_GOODS} 分开，沿用勋章那条口径：让协会<b>能够</b>把「录入」与「批准」
     * 分给不同的人——合成一个点则连分开的可能都没有。</p>
     *
     * <p><b>但拆分本身不构成「不得自审」</b>：同一账号可以兼有两点，超管更是通配全有。
     * 要强制双人复核得再加创建人字段并校验，本批<b>没有</b>做——与勋章、奖惩同一状态。
     * 别把这里的措辞读成系统保证。</p>
     */
    public static final String DONATE_GOODS_AUDIT = "donate:goods-audit";

    /** 兑换单查看（后台列表与详情，只读） */
    public static final String DONATE_ORDER = "donate:order";

    /**
     * 兑换<b>审核</b>（通过 / 驳回）。
     *
     * <p>与只读的 {@link #DONATE_ORDER} 分开，沿用 V24「查看与调整分开」的口径：
     * 驳回会退分、还库存，属于会改动账本的动作。</p>
     */
    public static final String DONATE_ORDER_AUDIT = "donate:order-audit";

    /**
     * 取货码现场核销。
     *
     * <p>与审核分开是因为<b>人不一样</b>：审核在办公室，核销在发放现场。
     * 卷批的核销员（Row 8 F）也复用这个点。</p>
     */
    public static final String DONATE_VERIFY = "donate:verify";
}
