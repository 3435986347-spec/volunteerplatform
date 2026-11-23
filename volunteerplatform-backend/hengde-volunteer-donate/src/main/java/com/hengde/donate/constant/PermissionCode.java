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

    /**
     * 卷管理与发放（卷批 V46，Row 8 F「发放卷功能、批量发卷功能」）。
     *
     * <p><b>建卷与发卷同一个点</b>：「能建卷却不能发」或反过来都没有现实场景。
     * 核销员指派不走这个点，复用 {@link #DONATE_VERIFY}——指派核销员的人就是管现场核销的人。</p>
     */
    public static final String DONATE_COUPON = "donate:coupon";

    /**
     * 捐赠物资流转与搜索（捐书批 V50）：扫码到货 / 核对 / 生成专属码 / 装箱 / 送达 / 退回、10 维搜索，
     * 以及捐书活动、受赠单位、商品条码库的维护。
     *
     * <p><b>活动与主数据维护没有单拆</b>：Row 17 F 只写了「搜索、导出」两类后台诉求，没有「谁能建活动」
     * 的现实分工依据——拆权限要有「谁能改 A 但不能改 B」的现实需求（兑换规则那条同一个判断）。</p>
     */
    public static final String DONATE_ITEM = "donate:item";

    /**
     * 捐赠物资批量导出。与 {@link #DONATE_ITEM} 分开：导出列含捐赠人名字、电话、单位、志愿者码链接，
     * 是与志愿者名册同一类的数据（同 {@code user:list} / {@code user:export} 的先例）。
     */
    public static final String DONATE_ITEM_EXPORT = "donate:item-export";

    /**
     * 微心愿管理（微心愿批 V51）：导入 / 单独上传 / 修改 / 下架 / 撤销认领 / 反馈发放 / 批量下载。
     *
     * <p><b>批量下载没有单拆</b>（与 {@link #DONATE_ITEM_EXPORT} 的判断相反，理由也相反）：
     * 捐书那边拆，是因为「能扫码流转的人」不必看得到捐赠人的电话与志愿者码；
     * 这里能管心愿的人在详情里本就看得到受助人的全部资料，拆一个导出点买不到任何隔离。</p>
     */
    public static final String DONATE_WISH = "donate:wish";

    /**
     * 结对与众筹的项目管理（结对批 V52）：项目增删改、上架 / 结束、结对登记的确认与取消、受助方来信录入。
     *
     * <p><b>结对与众筹共用一个点</b>：两者都是「项目管理」，没有「能管结对但不能管众筹」的现实分工——
     * 拆权限的判据一直是这个（兑换规则、微心愿导出都按同一条判断过）。</p>
     */
    public static final String DONATE_PROJECT = "donate:project";
}
