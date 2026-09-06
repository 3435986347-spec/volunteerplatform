package com.hengde.api.config;

import com.hengde.api.config.SaTokenConfigure.Access;
import com.hengde.common.constant.UserStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 禁用账号「小口子」的放行清单。纯函数，不需要 Docker、不起 Spring 上下文。
 *
 * <p><b>为什么这份清单值得专门测</b>：与 {@code DenyAllUseGateTest} 同一理由——它是
 * 「默认全挡 + 显式放行」，两个方向都会静默出错。少一条，被禁用的人连申诉都提交不了
 * （而那正是协会开这个口子要解决的问题）；多一条，禁用账号就能去做它不该做的事。
 * 两种错误都不抛异常、不进日志，只会体现在某个被禁用的人身上。</p>
 *
 * <p><b>本类还负责钉住「两份清单不能合并」这个决定</b>——见
 * {@link #twoGatesMustNotBeMerged}。</p>
 *
 * @author hengde
 */
class BannedAccountGateTest {

    /**
     * 放行清单的<b>两个方向</b>都钉住：该放的放、该挡的挡。
     *
     * <p>把 {@code EXEMPT_PATHS} 里任一条删掉，或图省事改成 {@code /v/auth/**}、
     * {@code /v/honor/**}，本用例立刻变红。</p>
     */
    @Test
    void exemptList_opensOnlyTheAppealPathAndLogout() {
        // 必须放行——理由见 BannedAccountGate.EXEMPT_PATHS 的逐条说明
        assertTrue(BannedAccountGate.isExempt("/v/auth/logout"),
                "不放行退出登录，被禁用的人连退出都做不到");
        assertTrue(BannedAccountGate.isExempt("/v/honor/reward-punishes"),
                "「只能看奖惩」的字面兑现");
        assertTrue(BannedAccountGate.isExempt("/v/honor/reward-punishes/123/appeal"),
                "「和提申诉」的字面兑现（Row 41 F 的 7 天申诉期）");
        assertTrue(BannedAccountGate.isExempt("/v/honor/sanctions"),
                "看得到自己被限制到几号；只说不能用不说到几号，是把有期限说成无期限");
        assertTrue(BannedAccountGate.isExempt("/v/notifications"),
                "站内提示；AntPathMatcher 的 /** 也匹配裸路径");
        assertTrue(BannedAccountGate.isExempt("/v/notifications/unread-count"));
        assertTrue(BannedAccountGate.isExempt("/v/notifications/9/read"));
        // V45：申诉凭证上传，理由同 DenyAllUseGate——被禁用的人一样有 7 天申诉期，
        // 提得出申诉却传不了凭证，那条路径就是残的。
        assertTrue(BannedAccountGate.isExempt("/v/files/appeal-image"), "申诉凭证上传");

        // 必须挡住
        assertFalse(BannedAccountGate.isExempt("/v/auth/register"),
                "被禁用的游客账号不该还能完成实名注册——这正是不复用 DenyAllUseGate 清单的原因");
        assertFalse(BannedAccountGate.isExempt("/v/auth/password"),
                "改密码不是申诉的必要条件");
        assertFalse(BannedAccountGate.isExempt("/v/honor/rankings"), "排行榜；写成 /v/honor/** 就会在这里漏");
        assertFalse(BannedAccountGate.isExempt("/v/files/profile-image"),
                "头像/i志愿者码上传不放行——放开整条 /v/files/** 会让被禁用的账号还能改头像换 i 码；"
                + "申诉凭证单开一个端点正是为了不牵连它");
        assertFalse(BannedAccountGate.isExempt("/v/honor/medals"), "勋章；同上");
        assertFalse(BannedAccountGate.isExempt("/v/honor/certificates"), "证书；同上");
        assertFalse(BannedAccountGate.isExempt("/v/activity/activities"), "浏览/报名活动");
        assertFalse(BannedAccountGate.isExempt("/v/user/profile"), "改资料");
        assertFalse(BannedAccountGate.isExempt("/v/organization/groups"), "小组申请");
        assertFalse(BannedAccountGate.isExempt(null));
    }

    /**
     * 登录类端点<b>不在</b>这份清单里，而且这不是遗漏。
     *
     * <p>它们本就在 {@code SaTokenConfigure} 第一道的公开 {@code notMatch} 列表中，
     * 走不到 {@code checkVolunteerEnabled}。写进来是死条目，只会让人误以为它在起作用——
     * 「禁用账号能不能登录」由 {@code VolunteerAuthService.ensureLoginable} 决定。</p>
     *
     * <p>本用例的作用是<b>拦住那个很自然的"补全"冲动</b>：有人看到清单里只有 logout 没有 login，
     * 会顺手补一条 {@code /v/auth/login/**}，甚至直接写成 {@code /v/auth/**}——
     * 而后者会把注册一起放开。</p>
     */
    @Test
    void loginEndpointsAreDeliberatelyAbsent() {
        assertFalse(BannedAccountGate.isExempt("/v/auth/login/sms"));
        assertFalse(BannedAccountGate.isExempt("/v/auth/login/wechat"));
        assertFalse(BannedAccountGate.isExempt("/v/auth/password/reset"));
    }

    /**
     * 两道闸门的清单<b>必须保持是两份</b>，它们面对的是不同人群。
     *
     * <p>{@code ALL} 处置针对的是<b>账号正常</b>但被判了最重一档的人，所以它放行
     * {@code /v/auth/**}（那一段里除登录外还有注册与改密，对账号正常的人无害）；
     * 禁用针对的是<b>账号本身已被停用</b>的人，放开整段就等于允许被禁用的游客完成实名注册。
     * 两份清单只在「能申诉」这件事上重合。</p>
     *
     * <p>哪天有人把两个数组指向同一份常量，本用例会红——那正是要拦的那次改动。</p>
     */
    @Test
    void twoGatesMustNotBeMerged() {
        assertNotEquals(DenyAllUseGate.EXEMPT_PATHS.length, 0);
        assertTrue(DenyAllUseGate.isExempt("/v/auth/register"),
                "夹具自检：ALL 那份清单确实放行注册（它写的是 /v/auth/**）");
        assertFalse(BannedAccountGate.isExempt("/v/auth/register"),
                "禁用那份必须不放行——两份清单一旦合并，这一条就是漏出去的那个洞");

        // 【必须是包含关系，不是「有交集」】禁用清单里的每一条都得同时被 ALL 清单放行。
        // 一个人可以既被禁用、又背着一张「拒绝使用本程序」的处罚——两道闸门串在同一条请求链上，
        // 任一道挡住，他就申诉不了。少一条不会报错，只会让这种人静默地失去申诉权。
        for (String shared : new String[]{
                "/v/auth/logout",
                "/v/honor/reward-punishes",
                "/v/honor/reward-punishes/7/appeal",
                "/v/honor/sanctions",
                "/v/notifications",
                "/v/notifications/3/read"}) {
            assertTrue(BannedAccountGate.isExempt(shared), "禁用态下应放行：" + shared);
            assertTrue(DenyAllUseGate.isExempt(shared),
                    "禁用清单里的每一条都必须同时被 ALL 清单放行，否则「既被禁用又被拒绝使用本程序」"
                            + "的人会两头都够不着申诉：" + shared);
        }
    }

    /**
     * 账号状态 × 路径 → 放行 / 拒绝 / 拒绝并登出。
     *
     * <p><b>最要紧的一格是「禁用 + 非放行路径 → 拒绝但<u>不</u>登出」</b>。
     * 把它写成 {@code DENY_AND_LOGOUT} 不会有任何征兆：接口照样返回 403，文案一模一样，
     * 只是那个人手上的 token 被作废了——他下一次点开「我的奖惩」拿到的是 401，
     * <b>小口子被自己关上，而放行清单看着还写得好好的</b>。
     * 这一位没有第二处代码能证伪它，只能由用例钉住。</p>
     *
     * <p>兜底那一格同理：把 {@code decide} 最后一行改成 {@code PASS}，
     * 一个 {@code status} 为 NULL 或将来新增取值的账号就会畅通无阻。</p>
     */
    @Test
    void decide_coversEveryStatusAndKeepsTheBannedSessionAlive() {
        String open = "/v/honor/reward-punishes/1/appeal";
        String closed = "/v/activity/activities";

        assertEquals(Access.PASS, SaTokenConfigure.decide(UserStatus.NORMAL, closed),
                "正常账号一路畅通");
        assertEquals(Access.PASS, SaTokenConfigure.decide(UserStatus.BANNED, open),
                "禁用账号在放行清单内照常可用");
        assertEquals(Access.DENY_KEEP_SESSION, SaTokenConfigure.decide(UserStatus.BANNED, closed),
                "禁用账号越界要拒绝，但【必须保留登录态】——登出会让上一行那条申诉路径变成 401");
        assertEquals(Access.DENY_AND_LOGOUT, SaTokenConfigure.decide(UserStatus.DELETED, open),
                "注销账号连放行清单也不给：账号已经没了，没有处罚要看也没有申诉要提");
        assertEquals(Access.DENY_AND_LOGOUT, SaTokenConfigure.decide(null, open),
                "行不存在（含逻辑删除）");
        assertEquals(Access.DENY_AND_LOGOUT, SaTokenConfigure.decide(99, open),
                "不认识的状态取值必须拒绝而不是放行——这一支是白名单的反面，不是异常分支");
    }
}
