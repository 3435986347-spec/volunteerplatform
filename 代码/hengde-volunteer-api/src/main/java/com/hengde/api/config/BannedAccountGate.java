package com.hengde.api.config;

import org.springframework.util.AntPathMatcher;

/**
 * 账号被<b>禁用</b>（{@code status = 1}）期间仍然放行的志愿者端路径——协会所说的那个「小口子」。
 *
 * <p><b>需求出处</b>：协会 2026-08-11 答复第 5 条「账号类处罚：由理事会审核后才生效，
 * <b>只给禁用账号开个小口子、只能看奖惩和提申诉</b>」。</p>
 *
 * <p><b>它推翻了什么</b>：在此之前，禁用账号在
 * {@code VolunteerAuthService.ensureLoginable} 就被拒发 token，连登录都进不来。
 * 于是「审核通过 + 7 天申诉期开始计时」这个动作，对一个禁用账号来说是
 * <b>处罚立即生效、而申诉期在他够不到的地方流逝</b>；禁用超过 7 天，申诉权就在不可达状态下过期了。
 * 第 6 轮评审当时的对策是「禁用期间一律不批」，协会选了另一条路：照常批，但把申诉这条路留着。
 * 那条对策的<b>理由并没有被推翻</b>——「申诉期在够不到的地方流逝」正是本闸门要消除的东西，
 * 只是消除的手段从「不批」换成了「开口子」。</p>
 *
 * <p><b>为什么不复用 {@link DenyAllUseGate#EXEMPT_PATHS}</b>：两道闸门面对的是<b>不同人群</b>，
 * 只是恰好在「能申诉」这件事上重合。{@code ALL} 处置针对的是<b>账号正常</b>但被判了最重一档的人，
 * 所以它放行 {@code /v/auth/**}（那一段里除了登录还有注册与改密，对账号正常的人无害）；
 * 禁用针对的是<b>账号本身已被停用</b>的人，把 {@code /v/auth/**} 整段放开就等于允许
 * 一个被禁用的游客账号去完成实名注册（{@code POST /v/auth/register}）。
 * 合并成一份清单意味着此后往任一份里加路径都要同时替另一种人考虑一遍——而那正是漏网的来源。</p>
 *
 * <p><b>为什么登录类端点不在这份清单里</b>：它们本来就在
 * {@code SaTokenConfigure} 第一道的公开 {@code notMatch} 列表中，
 * 走不到 {@code checkVolunteerEnabled}。写进来是死条目，只会让人误以为它在起作用。
 * 「禁用账号能不能登录」由 {@code VolunteerAuthService.ensureLoginable} 决定，不归本闸门管。</p>
 *
 * @author hengde
 */
public final class BannedAccountGate {

    /**
     * 禁用期间<b>仍然放行</b>的路径。每一条都要有理由：
     *
     * <ul>
     *   <li>{@code /v/auth/logout} —— 退出登录。不放行的话，被禁用的人连退出都做不到，
     *       只能靠清缓存。</li>
     *   <li>{@code /v/honor/reward-punishes}、{@code /v/honor/reward-punishes/&#42;/appeal} ——
     *       「只能看奖惩和提申诉」的字面兑现（Row 41 F 的 7 天申诉期）。</li>
     *   <li>{@code /v/honor/sanctions} —— 看得到自己被限制到什么时候。
     *       只说「不能用」不说「到几号」，是把有期限的处置说成了无期限的。</li>
     *   <li>{@code /v/notifications/**} —— 站内提示。告知处罚成立与申诉期限的那条就在这里；
     *       挡掉它，申诉期的起点就送不到人手上。</li>
     * </ul>
     *
     * <p><b>刻意不含</b>：{@code /v/auth/register}（禁用账号不该完成实名注册）、
     * {@code PUT /v/auth/password}（改密码不是申诉的必要条件）、
     * 以及 {@code /v/honor/**} 下的排行榜、勋章、证书——那些正是「使用本程序」的部分。</p>
     */
    public static final String[] EXEMPT_PATHS = {
            "/v/auth/logout",
            "/v/honor/reward-punishes",
            "/v/honor/reward-punishes/*/appeal",
            "/v/honor/sanctions",
            "/v/notifications/**",
    };

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private BannedAccountGate() {
    }

    /**
     * 该路径是否在禁用态的放行清单里。
     *
     * <p>抽成静态方法<b>只为让清单能被测试直接钉住</b>：漏一条（申诉被挡）与多一条
     * （把注册也放开了）都不会有任何运行期征兆，只会在某个被禁用的人身上表现为
     * 「该拦的没拦 / 该放的没放」。与 {@link DenyAllUseGate#isExempt} 同一理由、同一形状。</p>
     */
    public static boolean isExempt(String path) {
        if (path == null) {
            return false;
        }
        for (String pattern : EXEMPT_PATHS) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
}
