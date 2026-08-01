package com.hengde.honor.constant;

/**
 * honor 域权限点编码（与 V25 迁移 permission 表预置数据一致）。
 *
 * <p>管理端接口用 {@code @SaCheckPermission(value=..., type="admin")} 引用；
 * 权限数据由 organization 的 StpInterface 提供，超管走 {@code *} 万能码放行。</p>
 *
 * <p>志愿者端 {@code /v/honor/rankings} <b>不挂权限点</b>——榜单是给全体志愿者看的，只需登录。</p>
 *
 * @author hengde
 */
public final class PermissionCode {

    private PermissionCode() {
    }

    /** 后台查看排行榜 */
    public static final String HONOR_RANKING_VIEW = "honor:ranking-view";

    /**
     * 生成 / 补跑排行榜快照。
     *
     * <p>与查看分开，沿用 V24「查看与调整分开」的口径：补跑会按<b>当前</b>数据重算某个历史周期，
     * 等于改写已经公示过的历史名次，属高危操作。</p>
     */
    public static final String HONOR_RANKING_SNAPSHOT = "honor:ranking-snapshot";

    /** 勋章定义管理（含图标上传 {@code dir=medal}） */
    public static final String HONOR_MEDAL = "honor:medal";

    /**
     * 勋章<b>样式</b>审核。
     *
     * <p>与 {@link #HONOR_MEDAL} 分开，是为了让协会<b>能够</b>把「录入」与「批准」分给不同的人——
     * 合成一个点则连分开的可能都没有。</p>
     *
     * <p><b>但权限拆分本身不构成「不得自审」</b>：同一个账号完全可以被同时授予这两个点，
     * 超管更是通配 {@code *} 全有。真要强制双人复核，得再加「创建人」字段并在审核时校验
     * {@code reviewBy != createBy}——本批<b>没有</b>做这件事，它是协会的管理制度问题，
     * 不是技术上已经拦住的事。别把这里的措辞读成系统保证。</p>
     */
    public static final String HONOR_MEDAL_AUDIT = "honor:medal-audit";

    /** 发起勋章发放 */
    public static final String HONOR_MEDAL_GRANT = "honor:medal-grant";

    /** 勋章<b>发放</b>审核（第二重）；通过后志愿者才看得到，附带积分也在此刻入账 */
    public static final String HONOR_MEDAL_GRANT_AUDIT = "honor:medal-grant-audit";

    /** 榜样管理 */
    public static final String HONOR_ROLE_MODEL = "honor:role-model";

    /** 证书查询与批量上传（第 4 批） */
    public static final String HONOR_CERTIFICATE = "honor:certificate";

    /**
     * 证书<b>删除与恢复</b>（第 4 批）。
     *
     * <p>与 {@link #HONOR_CERTIFICATE} 分开：查询/上传是日常运营动作，
     * 而删除会让志愿者手里的证书凭空消失，属高危操作，沿用 V24「查看与调整分开」的口径。
     * 恢复与删除同点——能删的人才需要能撤销自己的误删。</p>
     */
    public static final String HONOR_CERTIFICATE_DELETE = "honor:certificate-delete";

    /** 证书电子样本管理（第 4 批，Row 36 F 列第 ③ 项） */
    public static final String HONOR_CERTIFICATE_TEMPLATE = "honor:certificate-template";
}
