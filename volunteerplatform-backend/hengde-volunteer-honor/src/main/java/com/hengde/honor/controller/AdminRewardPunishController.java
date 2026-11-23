package com.hengde.honor.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.entity.VolunteerSanction;
import com.hengde.auth.service.SanctionQueryService;
import com.hengde.auth.service.SanctionService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.common.result.ResultCode;
import com.hengde.honor.constant.PermissionCode;
import com.hengde.honor.dto.AppealHandleDTO;
import com.hengde.honor.dto.RejectReasonDTO;
import com.hengde.honor.dto.RewardPunishSaveDTO;
import com.hengde.honor.service.RewardPunishService;
import com.hengde.honor.vo.RewardPunishVO;
import com.hengde.auth.config.StpAdminUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Predicate;

/**
 * 管理端-奖惩中心（{@code /a/honor/reward-punishes}）。
 *
 * <p>xlsx Row 41 F「各类违规记录和奖励均需<b>组织部同学审核才可显示</b>，
 * 审核之后，志愿者会收到提示，并有 <b>7 天申诉期</b>」。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-奖惩中心")
@RestController
@RequestMapping("/a/honor")
public class AdminRewardPunishController {

    private RewardPunishService rewardPunishService;
    private SanctionService sanctionService;
    private SanctionQueryService sanctionQueryService;

    @Autowired
    public void setRewardPunishService(RewardPunishService rewardPunishService) {
        this.rewardPunishService = rewardPunishService;
    }

    @Autowired
    public void setSanctionService(SanctionService sanctionService) {
        this.sanctionService = sanctionService;
    }

    @Autowired
    public void setSanctionQueryService(SanctionQueryService sanctionQueryService) {
        this.sanctionQueryService = sanctionQueryService;
    }

    /**
     * <p><b>放宽为「管理权或申诉受理权」二者其一</b>：只有 {@code reward-punish-appeal} 的受理人
     * 若读不到列表，就只能盲审——他拿不到待受理的单子，也看不到自己要判的那张单的原委。
     * 这与第 3 批勋章「只有审核权却读不到列表」是同一条。</p>
     *
     * <p><b>但放宽必须同时收窄可见面</b>：只有受理权的人只看得到<b>已进入申诉流程</b>的单子，
     * 看不到待审核的草稿与别人尚未批的处罚。「不盲审」要的是看到自己要判的那一张，不是看到全部——
     * 第一次改这条时只做了放宽、忘了收窄。</p>
     *
     * <p><b>终审权（理事会）看全部</b>，与管理权同口径（url文档v2 一直是这么写的）。代码里曾只按
     * 「有没有管理权」判，只持 {@code reward-punish-final} 的账号过得了注解、却被当成只有受理权收窄成
     * 只看已申诉的单——「待终审」页签与侧栏角标永远是空的，没有任何报错。</p>
     */
    @Operation(summary = "奖惩单列表（可按志愿者/类型/审核状态/申诉状态筛）")
    @SaCheckPermission(value = {PermissionCode.HONOR_REWARD_PUNISH, PermissionCode.HONOR_REWARD_PUNISH_APPEAL,
            PermissionCode.HONOR_REWARD_PUNISH_FINAL}, mode = SaMode.OR, type = "admin")
    @GetMapping("/reward-punishes")
    public Result<PageResult<RewardPunishVO>> list(PageQuery query,
                                                   @Parameter(description = "志愿者 id") @RequestParam(required = false) Long volunteerId,
                                                   @Parameter(description = "1奖励/2处罚") @RequestParam(required = false) Integer type,
                                                   @Parameter(description = "审核 0待初审/3待终审/1已通过/2已驳回") @RequestParam(required = false) Integer reviewStatus,
                                                   @Parameter(description = "申诉 0未申诉/1申诉中/2成立/3驳回") @RequestParam(required = false) Integer appealStatus) {
        boolean appealedOnly = appealedOnly(
                StpAdminUtil.STP_LOGIC.hasPermission(PermissionCode.HONOR_REWARD_PUNISH),
                StpAdminUtil.STP_LOGIC.hasPermission(PermissionCode.HONOR_REWARD_PUNISH_FINAL));
        return Result.ok(rewardPunishService.adminList(query, volunteerId, type, reviewStatus,
                appealStatus, appealedOnly));
    }

    /**
     * 「是否只给看已申诉的单」的判定，单独成方法<b>只为让极性能被测试钉住</b>。
     *
     * <p>这一位取反写错不会有任何编译或运行期征兆：传 {@code false} 时接口照常返回数据，
     * 只是把待审核草稿一并交给了只有申诉受理权的人——而 service 层的用例
     * 只能证明「传 true 会收窄」，证明不了 controller 传的是哪个值。
     * 抽成静态方法后，{@code AdminRewardPunishControllerTest} 可以直接对它断言。</p>
     *
     * @param hasManagePermission 是否持有 {@code honor:reward-punish}（完整管理权）
     * @param hasFinalPermission  是否持有 {@code honor:reward-punish-final}（终审权）
     * @return true = 只看得到已进入申诉流程的单
     */
    static boolean appealedOnly(boolean hasManagePermission, boolean hasFinalPermission) {
        return !hasManagePermission && !hasFinalPermission;
    }

    /**
     * <p>处罚可由一条现场违规转来（{@code violationId}），但那条违规<b>必须已通过组织部审核</b>——
     * 未经核实的现场记录只是负责人的一面之词，不够格作为处罚依据。</p>
     *
     * <p><b>每一档限制要对应部门的权限</b>：见 {@link #assertScopeAllowed}。</p>
     */
    @Operation(summary = "开一张奖惩单（落待审核；处罚可关联已审核通过的现场违规）")
    @SaCheckPermission(value = PermissionCode.HONOR_REWARD_PUNISH, type = "admin")
    @PostMapping("/reward-punishes")
    public Result<Long> create(@RequestBody @Valid RewardPunishSaveDTO dto) {
        assertScopeAllowed(dto.getSanctionScope(), StpAdminUtil.STP_LOGIC::hasPermission);
        // 【快捷通道的判据是开单人的权限，不是请求体里的开关】持终审权 = 理事会开单 = 开即通过。
        // 做成开关的话，任何有开单权的人都能给自己开一条免审通道，两级审核就成了自愿参加的。
        // 与 assertScopeAllowed 同一形状：能不能这么做是【授权】问题，
        // 而 @SaCheckPermission 表达不了「取决于开单人是谁」。
        boolean byFinalApprover =
                StpAdminUtil.STP_LOGIC.hasPermission(PermissionCode.HONOR_REWARD_PUNISH_FINAL);
        return Result.ok(rewardPunishService.create(dto, StpAdminUtil.getLoginIdAsLong(), byFinalApprover));
    }

    /**
     * 每一档限制由对应部门开出（协会 2026-09-16）：
     * 限制参加活动 → 组织部（{@code honor:sanction-activity}）/ 限制发布社区及其细分档 → 宣传部
     * （{@code honor:sanction-community}）/ 拒绝使用本程序 → 秘书部（{@code honor:sanction-all}）；
     * <b>理事会拥有全部限制权限</b>＝三个点都授给理事会的账号。
     *
     * <p>需求表 Row 73 原写「可由该部门负责的同学限制其使用……监察部拥有全部限制能力」——监察部一年前已撤销。
     * 此前（V36）只拆出了最重的一档，「限制参加活动」「限制发布社区」跟着开单权走，任何能开单的人都能开。</p>
     *
     * <p><b>为什么不能写成 {@code @SaCheckPermission}</b>：它拦的是整个方法，而这里的判定取决于
     * <b>请求体里的 {@code sanctionScope}</b>——挂在方法上会把不附带处置的单（含全部奖励）也一并锁死。</p>
     *
     * <p><b>为什么单独成静态方法</b>：这一位判错不会有任何编译或运行期征兆（接口照常返回单号，只是谁都能开），
     * 而 service 层用例根本触碰不到 controller 传的是什么。抽出来才能被直接断言。</p>
     *
     * @param sanctionScope 请求体里的处置能力域，可为 {@code null}（不附带处置）
     * @param hasPermission 当前账号是否持有某个权限点
     * @throws BusinessException 想开某一档限制却没有对应权限
     */
    static void assertScopeAllowed(Integer sanctionScope, Predicate<String> hasPermission) {
        assertScopeAllowed(sanctionScope, hasPermission, "开出");
    }

    /**
     * 同上，{@code action} 是报错里的动词（「开出」/「解除」）。<b>解除与开出同一档要同一个权限点</b>——
     * 否则任何持 {@code honor:sanction} 的账号都能把秘书部开的「拒绝使用本程序」提前解掉，部门分工只管住了一半。
     */
    static void assertScopeAllowed(Integer sanctionScope, Predicate<String> hasPermission, String action) {
        String required = requiredPermission(sanctionScope);
        if (required != null && !hasPermission.test(required)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(),
                    action + "「" + SanctionScope.labelOf(sanctionScope) + "」需要「" + required + "」权限"
                            + "（限制参加活动由组织部、限制发布社区由宣传部、拒绝使用本程序由秘书部负责，理事会拥有全部限制权限）");
        }
    }

    /** 某一档处置要求的权限点；不附带处置或取值未知时返回 {@code null}（未知取值由 service 的 {@code assertKnown} 拒绝）。 */
    static String requiredPermission(Integer sanctionScope) {
        if (sanctionScope == null) {
            return null;
        }
        return switch (sanctionScope) {
            case SanctionScope.ACTIVITY -> PermissionCode.HONOR_SANCTION_ACTIVITY;
            case SanctionScope.ALL -> PermissionCode.HONOR_SANCTION_ALL;
            case SanctionScope.COMMUNITY, SanctionScope.COMMUNITY_POST, SanctionScope.COMMUNITY_COMMENT,
                 SanctionScope.COMMUNITY_LIKE, SanctionScope.COMMUNITY_CHAT -> PermissionCode.HONOR_SANCTION_COMMUNITY;
            default -> null;
        };
    }

    /**
     * <b>初审</b>（组织部）：待初审 → 待终审。<b>不产生任何效力。</b>
     *
     * <p>协会 2026-09-02：「从下往上反馈的，由组织部的同学审核了，则到理事会审核」，
     * 且「理事会没审完，志愿者不会看到处罚」——所以这一步只是往前推一格，
     * 积分、处置、提示、申诉期计时全都还没开始。</p>
     */
    @Operation(summary = "初审通过（组织部；仅推进到待终审，不产生任何效力）")
    @SaCheckPermission(value = PermissionCode.HONOR_REWARD_PUNISH, type = "admin")
    @PostMapping("/reward-punishes/{id}/approve")
    public Result<Void> firstApprove(@PathVariable Long id) {
        rewardPunishService.firstApprove(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    /**
     * <b>终审</b>（理事会）：待终审 → 已通过。效力在这一刻全部落地。
     *
     * <p>持有本权限的人开单时会走「开即通过」的快捷通道（见 {@link #create}），
     * 那条路与本接口产生的结果逐字相同。</p>
     */
    @Operation(summary = "终审通过（理事会；此刻起对志愿者可见、积分入账、处置生效、申诉期开始计时）")
    @SaCheckPermission(value = PermissionCode.HONOR_REWARD_PUNISH_FINAL, type = "admin")
    @PostMapping("/reward-punishes/{id}/final-approve")
    public Result<Void> finalApprove(@PathVariable Long id) {
        rewardPunishService.finalApprove(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    /**
     * 驳回：<b>初审与终审两档都可以驳</b>。
     *
     * <p>只让初审驳的话，一张已初审的单到了理事会手上就只剩「批」这一条路。
     * 权限上兼容两方：持开单/初审权或终审权都可驳回。</p>
     */
    @Operation(summary = "审核驳回（初审、终审均可；须填原因；志愿者始终看不到这张单）")
    @SaCheckPermission(value = {PermissionCode.HONOR_REWARD_PUNISH, PermissionCode.HONOR_REWARD_PUNISH_FINAL},
            mode = SaMode.OR, type = "admin")
    @PostMapping("/reward-punishes/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @RequestBody @Valid RejectReasonDTO dto) {
        rewardPunishService.reject(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    /**
     * <p><b>单独一个权限点</b>：需求没写申诉由谁受理，把它做成可授权的点，
     * 谁受理由后台配置决定，而不是在代码里替协会挑一个部门。</p>
     */
    @Operation(summary = "受理申诉（成立则撤销处置并冲正积分）")
    @SaCheckPermission(value = PermissionCode.HONOR_REWARD_PUNISH_APPEAL, type = "admin")
    @PostMapping("/reward-punishes/{id}/appeal")
    public Result<Void> handleAppeal(@PathVariable Long id, @RequestBody @Valid AppealHandleDTO dto) {
        rewardPunishService.handleAppeal(id, dto, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "查某人当前生效中的处置")
    @SaCheckPermission(value = PermissionCode.HONOR_SANCTION, type = "admin")
    @GetMapping("/sanctions")
    public Result<List<VolunteerSanction>> sanctions(@RequestParam Long volunteerId) {
        return Result.ok(sanctionQueryService.activeSanctions(volunteerId));
    }

    @Operation(summary = "提前解除某张奖惩单产生的处置（须填原因；还要有这一档对应部门的处置权限点）")
    @SaCheckPermission(value = PermissionCode.HONOR_SANCTION, type = "admin")
    @PostMapping("/reward-punishes/{id}/lift-sanction")
    public Result<Integer> liftSanction(@PathVariable Long id, @RequestBody @Valid RejectReasonDTO dto) {
        assertScopeAllowed(rewardPunishService.sanctionScopeOf(id), StpAdminUtil.STP_LOGIC::hasPermission, "解除");
        return Result.ok(sanctionService.liftBySource(VolunteerSanction.SOURCE_REWARD_PUNISH, id,
                StpAdminUtil.getLoginIdAsLong(), dto.getReason()));
    }
}
