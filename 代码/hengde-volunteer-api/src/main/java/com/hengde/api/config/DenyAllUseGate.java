package com.hengde.api.config;

import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.service.SanctionQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * 「拒绝其使用本程序」({@link SanctionScope#ALL}) 的<b>统一拦截点</b>。
 *
 * <p><b>需求出处</b>：xlsx Row 73「志愿者使用的前端功能如出现违规行为，可由该部门负责的同学
 * 限制其使用，包括但不限制于限制其使用指定天数、<b>拒绝其使用本程序</b>」。</p>
 *
 * <p><b>它补的是什么洞</b>：V32 建了 {@code ALL} 这个能力域，但真正查它的只有报名与签到那两道
 * 业务闸门（{@code SanctionQueryService.assertNotRestricted} 里 {@code ALL} 蕴含 {@code ACTIVITY}）。
 * 于是「拒绝使用本程序」实际只等同于「限制参加活动」——被判最重处罚的人照样能申请入组、
 * 提交管理团队问卷、发活动留言、改资料。<b>名实不符，而且从后台看不出来</b>：
 * 处罚记录上白纸黑字写着「拒绝使用本程序」。</p>
 *
 * <p><b>为什么是一道统一拦截而不是逐个 service 补 if</b>：能力域的语义是「所有业务功能」，
 * 逐个补的写法要求<b>今后每加一个志愿者端接口都记得补一次</b>，
 * 漏一个不会有任何征兆——这正是上面那个洞的成因。挂在 {@code /v/**} 上则默认全挡，
 * 要放行必须显式写进 {@link #EXEMPT_PATHS}，把「忘记」从静默漏网变成显式决定。</p>
 *
 * <p><b>放行清单不是可选项，是需求的一部分</b>：Row 41 F 给了志愿者 7 天申诉期，
 * 而申诉在小程序内提交。若最重的处置连奖惩记录与申诉一起挡掉，
 * <b>被罚得最重的人恰恰成了唯一无法申诉的人</b>，申诉权形同虚设。
 * 站内提示同理——告知「处罚成立、7 天内可申诉」的那条提示本身就在那里。</p>
 *
 * <p><b>成本</b>：每个 {@code /v/**} 请求多一次按 {@code idx_volunteer_scope} 的点查。
 * 没有加缓存：处置的生效与解除都要求<b>即时</b>（「到期即自动恢复」是对志愿者的承诺），
 * 而缓存会引入一段「已解除但仍被挡」的窗口，那比多一次索引查询糟得多。</p>
 *
 * @author hengde
 */
@Component
public class DenyAllUseGate {

    /**
     * {@code ALL} 生效期间<b>仍然放行</b>的志愿者端路径。
     *
     * <p>每一条都要有理由，不是「顺手放开」：</p>
     * <ul>
     *   <li>{@code /v/auth/**} —— 登录与登出。挡掉登录，下面几条也就都够不着了。</li>
     *   <li>{@code /v/honor/reward-punishes}、{@code /v/honor/reward-punishes/&#42;/appeal} ——
     *       奖惩记录与申诉入口（Row 41 F 的 7 天申诉期）。
     *       <b>刻意不写成 {@code /v/honor/**}</b>：那会把排行榜、勋章、证书一起放行，
     *       而那些正是「使用本程序」的部分，应当挡住。</li>
     *   <li>{@code /v/honor/sanctions} —— 让他看得到自己被限制到什么时候。
     *       只告诉他「不能用」却不告诉他「到几号」，是把有期限的处罚说成了无期限的。</li>
     *   <li>{@code /v/notifications/**} —— 站内提示。告知处罚成立与申诉期限的那条就在这里。</li>
     * </ul>
     */
    public static final String[] EXEMPT_PATHS = {
            "/v/auth/**",
            "/v/honor/reward-punishes",
            "/v/honor/reward-punishes/*/appeal",
            "/v/honor/sanctions",
            "/v/notifications/**",
    };

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private SanctionQueryService sanctionQueryService;

    @Autowired
    public void setSanctionQueryService(SanctionQueryService sanctionQueryService) {
        this.sanctionQueryService = sanctionQueryService;
    }

    /**
     * 拦截判定。被 {@code ALL} 处置且访问的不是放行路径 → 抛 403。
     *
     * <p><b>用 {@code isRestricted}（快照读）而不是 {@code assertNotRestricted}</b>：
     * 拦截器跑在事务之外，{@code FOR SHARE} 在 autocommit 下随语句释放，加了也白加；
     * 而这里要的是「拦住展示与浏览」，不是与某次写入串行化——真正需要串行化的写动作
     * （报名、签到）在各自的 service 里另有那道带锁的闸门，两者不重复也不冲突。</p>
     *
     * <p><b>{@code volunteerId} 为 null（未登录）直接放行，这一支是真防线不是摆设</b>：
     * 调用方传的是 {@code StpUtil.getLoginIdDefaultNull()} 而非 {@code getLoginIdAsLong()}，
     * 后者在未登录时会先抛 {@code NotLoginException}，这一支就永远走不到。
     * 区别在于系统正确性是否依赖<b>两张分开维护的清单恰好对齐</b>——
     * 登录校验的公开路径列表与本类的 {@link #EXEMPT_PATHS}。取 null 之后两者解耦：
     * 「这个端点要不要登录」不归本闸门管，它只回答「已登录的这个人是否被拒绝使用本程序」。</p>
     *
     * @param path        请求路径（不含 contextPath）
     * @param volunteerId 当前登录志愿者；未登录为 {@code null}
     */
    public void check(String path, Long volunteerId) {
        if (volunteerId == null || isExempt(path)) {
            return;
        }
        if (sanctionQueryService.isRestricted(volunteerId, SanctionScope.ALL)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(),
                    "您当前被" + SanctionScope.labelOf(SanctionScope.ALL)
                            + "，暂不能使用该功能。可在奖惩记录中查看处罚详情与解除时间，并在申诉期内提交申诉");
        }
    }

    /**
     * 该路径是否在放行清单里。
     *
     * <p>抽成静态方法<b>只为让清单能被测试直接钉住</b>：漏一条（申诉被挡）与多一条
     * （{@code /v/honor/**} 把排行榜也放了）都不会有任何运行期征兆，
     * 只会在某个被处罚的人身上表现为「该拦的没拦 / 该放的没放」。</p>
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
