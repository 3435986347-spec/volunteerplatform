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
     */
    @Operation(summary = "奖惩单列表（可按志愿者/类型/审核状态/申诉状态筛）")
    @SaCheckPermission(value = {PermissionCode.HONOR_REWARD_PUNISH, PermissionCode.HONOR_REWARD_PUNISH_APPEAL},
            mode = SaMode.OR, type = "admin")
    @GetMapping("/reward-punishes")
    public Result<PageResult<RewardPunishVO>> list(PageQuery query,
                                                   @Parameter(description = "志愿者 id") @RequestParam(required = false) Long volunteerId,
                                                   @Parameter(description = "1奖励/2处罚") @RequestParam(required = false) Integer type,
                                                   @Parameter(description = "审核 0待审核/1已通过/2已驳回") @RequestParam(required = false) Integer reviewStatus,
                                                   @Parameter(description = "申诉 0未申诉/1申诉中/2成立/3驳回") @RequestParam(required = false) Integer appealStatus) {
        boolean appealedOnly = appealedOnly(
                StpAdminUtil.STP_LOGIC.hasPermission(PermissionCode.HONOR_REWARD_PUNISH));
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
     * @return true = 只看得到已进入申诉流程的单
     */
    static boolean appealedOnly(boolean hasManagePermission) {
        return !hasManagePermission;
    }

    /**
     * <p>处罚可由一条现场违规转来（{@code violationId}），但那条违规<b>必须已通过组织部审核</b>——
     * 未经核实的现场记录只是负责人的一面之词，不够格作为处罚依据。</p>
     *
     * <p><b>最重的那一档限制要额外授权</b>：见 {@link #assertScopeAllowed}。</p>
     */
    @Operation(summary = "开一张奖惩单（落待审核；处罚可关联已审核通过的现场违规）")
    @SaCheckPermission(value = PermissionCode.HONOR_REWARD_PUNISH, type = "admin")
    @PostMapping("/reward-punishes")
    public Result<Long> create(@RequestBody @Valid RewardPunishSaveDTO dto) {
        assertScopeAllowed(dto.getSanctionScope(),
                StpAdminUtil.STP_LOGIC.hasPermission(PermissionCode.HONOR_SANCTION_ALL));
        return Result.ok(rewardPunishService.create(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    /**
     * 「拒绝其使用本程序」只能由持有<b>全部限制能力</b>的账号开出。
     *
     * <p><b>需求出处</b> xlsx Row 73：「…可由该部门负责的同学限制其使用，包括但不限制于
     * 限制其使用指定天数、<b>拒绝其使用本程序</b>。<b>监察部拥有全部限制能力</b>」。
     * 只要拿到开单权就能填最重的一档，等于所有部门都拥有全部限制能力，
     * 那句话也就没有任何区分作用了。</p>
     *
     * <p><b>为什么不能写成 {@code @SaCheckPermission}</b>：它拦的是整个方法，
     * 而这里的判定取决于<b>请求体里的 {@code sanctionScope}</b>——挂在方法上会把
     * 「限制参加活动」这类日常处罚也一并锁死。</p>
     *
     * <p><b>为什么单独成静态方法</b>：与 {@link #appealedOnly} 同一理由——这一位判反了不会有任何
     * 编译或运行期征兆（接口照常返回单号，只是谁都能开出最重的限制），
     * 而 service 层用例根本触碰不到 controller 传的是什么。抽出来才能被直接断言。</p>
     *
     * @param sanctionScope             请求体里的处置能力域，可为 {@code null}（不附带处置）
     * @param hasAllSanctionPermission  是否持有 {@code honor:sanction-all}
     * @throws BusinessException 想开最重一档却没有该权限
     */
    static void assertScopeAllowed(Integer sanctionScope, boolean hasAllSanctionPermission) {
        if (sanctionScope != null && sanctionScope == SanctionScope.ALL && !hasAllSanctionPermission) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(),
                    "「拒绝其使用本程序」属全部限制能力，需要「" + PermissionCode.HONOR_SANCTION_ALL
                            + "」权限（Row 73：监察部拥有全部限制能力）");
        }
    }

    @Operation(summary = "审核通过（此刻起对志愿者可见、积分入账、处置生效、申诉期开始计时）")
    @SaCheckPermission(value = PermissionCode.HONOR_REWARD_PUNISH, type = "admin")
    @PostMapping("/reward-punishes/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        rewardPunishService.approve(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "审核驳回（须填原因；志愿者始终看不到这张单）")
    @SaCheckPermission(value = PermissionCode.HONOR_REWARD_PUNISH, type = "admin")
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

    @Operation(summary = "提前解除某张奖惩单产生的处置（须填原因）")
    @SaCheckPermission(value = PermissionCode.HONOR_SANCTION, type = "admin")
    @PostMapping("/reward-punishes/{id}/lift-sanction")
    public Result<Integer> liftSanction(@PathVariable Long id, @RequestBody @Valid RejectReasonDTO dto) {
        return Result.ok(sanctionService.liftBySource(VolunteerSanction.SOURCE_REWARD_PUNISH, id,
                StpAdminUtil.getLoginIdAsLong(), dto.getReason()));
    }
}
