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

    /** 奖惩记录管理与审核（第 5 批；Row 41 F「均需组织部同学审核才可显示」） */
    public static final String HONOR_REWARD_PUNISH = "honor:reward-punish";

    /**
     * 奖惩申诉受理（第 5 批）。
     *
     * <p><b>为什么单列一个点</b>：需求只写了审核方是组织部（Row 41 F），
     * <b>没有写申诉由谁受理</b>。与其在代码里替协会挑一个部门，不如把它做成独立权限点——
     * 谁受理由后台授权决定，协会改主意时改的是一次配置，不是一次代码。</p>
     *
     * <p>注意这只是让「分开授权成为可能」，<b>并不禁止同一账号兼有审核与受理两点</b>。
     * 要强制「审核人不得受理自己批的单」得另加人员校验，本批未做（与第 3 批勋章同一口径）。</p>
     */
    public static final String HONOR_REWARD_PUNISH_APPEAL = "honor:reward-punish-appeal";

    /**
     * 奖惩<b>理事会终审</b>（协会 2026-09-02 答复问题二）。
     *
     * <p>协会答复合起来是<b>两级审核 + 一条快捷通道，且奖与惩不对称</b>：</p>
     * <ul>
     *   <li>处罚 · 从下往上：{@link #HONOR_REWARD_PUNISH} 初审 → 本点终审；</li>
     *   <li>处罚 · 理事会开单（紧急）：开即通过；</li>
     *   <li>奖励 · 部门提出：直落待终审，<b>不经组织部</b>；</li>
     *   <li>奖励 · 理事会发起：开即通过。</li>
     * </ul>
     *
     * <p><b>本点同时是「快捷通道」的判据</b>：开单人持有它 ⇒ 视为理事会开单 ⇒ 开即通过。
     * 所以授予本点等于同时授予「免审开单」的能力，<b>授权时要当成一件事看</b>。</p>
     *
     * <p><b>为什么判据是权限而不是请求体里的一个开关</b>：做成开关的话，
     * 任何有开单权的人都能给自己开一条免审通道，两级审核就成了自愿参加的。
     * 与 {@link #HONOR_SANCTION_ALL} 同一形状——能不能这么做是<b>授权</b>问题。</p>
     *
     * <p>⚠️ 具体授给哪些账号是<b>上线前的授权工作</b>：现有权限点里没有「理事会」这一层
     * （五部门并成三部门之后，理事会在三部门之上）。见《协会待确认清单》回执三的新问题 G。</p>
     */
    public static final String HONOR_REWARD_PUNISH_FINAL = "honor:reward-punish-final";

    /** 处置措施解除（第 5 批；管理员主动撤销限制。申诉成立时由系统自动解除，不走这个点） */
    public static final String HONOR_SANCTION = "honor:sanction";

    /**
     * 开出「拒绝其使用本程序」({@code SanctionScope.ALL}) 的处罚需要本点。
     *
     * <p><b>谁来开</b>：协会 2026-09-16——限制参加活动 → 组织部 / 限制发布社区 → 宣传部 /
     * 拒绝使用本程序 → <b>秘书部</b> / 账号恢复 → 理事会，<b>理事会拥有全部限制权限</b>。
     * 需求表 Row 73 原写「监察部拥有全部限制能力」——监察部一年前已撤销，以协会答复为准。
     * 点名沿用 V36 的 {@code sanction-all}（改名会让已授权的账号全部失效），含义自 V85 起收窄为「拒绝使用本程序」这一档。</p>
     *
     * <p>三档各一个点，判定在 {@code AdminRewardPunishController.assertScopeAllowed}——能否开出某一档限制是
     * <b>授权</b>问题，与 service 的业务规则分开；{@code @SaCheckPermission} 表达不了「取决于请求体的某个字段」。</p>
     */
    public static final String HONOR_SANCTION_ALL = "honor:sanction-all";

    /** 开出「限制参加活动」({@code SanctionScope.ACTIVITY}) 的处罚（V85；协会 2026-09-16：组织部负责，理事会拥有全部限制权限）。 */
    public static final String HONOR_SANCTION_ACTIVITY = "honor:sanction-activity";

    /**
     * 开出「限制发布社区」({@code SanctionScope.COMMUNITY}) 及其细分档（发帖 / 评论 / 点赞 / 私信）的处罚
     * （V85；协会 2026-09-16：宣传部负责，理事会拥有全部限制权限）。社区里的禁言单另走 {@code social:ban}。
     */
    public static final String HONOR_SANCTION_COMMUNITY = "honor:sanction-community";
}
