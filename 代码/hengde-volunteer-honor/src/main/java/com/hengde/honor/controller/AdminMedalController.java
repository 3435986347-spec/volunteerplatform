package com.hengde.honor.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hengde.common.result.Result;
import com.hengde.honor.constant.PermissionCode;
import com.hengde.honor.dto.MedalGrantDTO;
import com.hengde.honor.dto.MedalSaveDTO;
import com.hengde.honor.dto.RejectReasonDTO;
import com.hengde.honor.service.MedalGrantService;
import com.hengde.honor.service.MedalService;
import com.hengde.honor.vo.MedalGrantVO;
import com.hengde.honor.vo.MedalVO;
import com.hengde.auth.config.StpAdminUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端-勋章（{@code /a/honor/medals}）。
 *
 * <p><b>双重审核在权限上的落点</b>：定义维护 {@code honor:medal}、样式审核 {@code honor:medal-audit}、
 * 发起发放 {@code honor:medal-grant}、发放审核 {@code honor:medal-grant-audit} 四个点各管一段。
 * 分开授权，「录入的人」与「批准的人」才<b>可能</b>不是同一个人——但系统并不禁止把两个点授给
 * 同一账号，也拦不住超管自审（详见 {@link PermissionCode#HONOR_MEDAL_AUDIT}）。</p>
 *
 * <p>勋章图标经通用上传 {@code POST /a/files/upload?dir=medal}（同样要 {@code honor:medal}）。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-勋章")
@RestController
@RequestMapping("/a/honor")
public class AdminMedalController {

    private MedalService medalService;
    private MedalGrantService medalGrantService;

    @Autowired
    public void setMedalService(MedalService medalService) {
        this.medalService = medalService;
    }

    @Autowired
    public void setMedalGrantService(MedalGrantService medalGrantService) {
        this.medalGrantService = medalGrantService;
    }

    // ---------- 定义 ----------

    // 读列表：定义管理(honor:medal) 或 样式审核(honor:medal-audit) 任一即可。
    // 只给审核权的账号若读不到列表，就只能拿着别人给的 id 盲审——审核材料都看不到，审核就是走过场。
    // 列表本身已含审核所需全部字段（图标/条件/积分/驳回原因/审核痕迹），status=1 一筛即待审队列，
    // 故不再单开详情接口。同口径见 ActivityManageAdminController 的负责人名单。
    @Operation(summary = "勋章列表（status 可筛选 0草稿/1待审核/2已启用/3已驳回/4已停用）")
    @SaCheckPermission(value = {PermissionCode.HONOR_MEDAL, PermissionCode.HONOR_MEDAL_AUDIT},
            mode = SaMode.OR, type = "admin")
    @GetMapping("/medals")
    public Result<List<MedalVO>> list(
            @Parameter(description = "状态筛选，不传=全部") @RequestParam(required = false) Integer status) {
        return Result.ok(medalService.listForAdmin(status));
    }

    @Operation(summary = "新增勋章定义（落草稿，需提交审核后才可发放）")
    @SaCheckPermission(value = PermissionCode.HONOR_MEDAL, type = "admin")
    @PostMapping("/medals")
    public Result<Long> create(@Valid @RequestBody MedalSaveDTO dto) {
        return Result.ok(medalService.create(dto));
    }

    @Operation(summary = "修改勋章定义（改已启用的会退回待审核，须重新过样式审核）")
    @SaCheckPermission(value = PermissionCode.HONOR_MEDAL, type = "admin")
    @PutMapping("/medals/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody MedalSaveDTO dto) {
        medalService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "调整勋章展示排序（纯展示，不影响审核状态）")
    @SaCheckPermission(value = PermissionCode.HONOR_MEDAL, type = "admin")
    @PutMapping("/medals/{id}/sort")
    public Result<Void> updateSort(@PathVariable Long id, @RequestParam Integer sort) {
        medalService.updateSort(id, sort);
        return Result.ok();
    }

    @Operation(summary = "删除勋章定义（已有待审/已生效发放记录的不可删，请改用停用）")
    @SaCheckPermission(value = PermissionCode.HONOR_MEDAL, type = "admin")
    @DeleteMapping("/medals/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        medalService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "提交样式审核（草稿/已驳回 → 待审核）")
    @SaCheckPermission(value = PermissionCode.HONOR_MEDAL, type = "admin")
    @PostMapping("/medals/{id}/submit")
    public Result<Void> submit(@PathVariable Long id) {
        medalService.submit(id);
        return Result.ok();
    }

    // ---------- 样式审核 ----------

    @Operation(summary = "样式审核通过（待审核 → 已启用，此后方可发放）")
    @SaCheckPermission(value = PermissionCode.HONOR_MEDAL_AUDIT, type = "admin")
    @PostMapping("/medals/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        medalService.approve(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "样式审核驳回（待审核 → 已驳回，可修改后重新提交）")
    @SaCheckPermission(value = PermissionCode.HONOR_MEDAL_AUDIT, type = "admin")
    @PostMapping("/medals/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody RejectReasonDTO dto) {
        medalService.reject(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "停用勋章（不可再发放；已生效的发放记录不受影响）")
    @SaCheckPermission(value = PermissionCode.HONOR_MEDAL_AUDIT, type = "admin")
    @PostMapping("/medals/{id}/disable")
    public Result<Void> disable(@PathVariable Long id) {
        medalService.disable(id);
        return Result.ok();
    }

    @Operation(summary = "重新启用勋章（该样式此前已过审，无需再审）")
    @SaCheckPermission(value = PermissionCode.HONOR_MEDAL_AUDIT, type = "admin")
    @PostMapping("/medals/{id}/enable")
    public Result<Void> enable(@PathVariable Long id) {
        medalService.enable(id);
        return Result.ok();
    }

    // ---------- 发放 ----------

    // 同上：发起权(honor:medal-grant) 或 发放审核权(honor:medal-grant-audit) 任一即可读
    @Operation(summary = "发放记录列表（status 0待审核/1已生效/2已驳回；可按志愿者筛选）")
    @SaCheckPermission(value = {PermissionCode.HONOR_MEDAL_GRANT, PermissionCode.HONOR_MEDAL_GRANT_AUDIT},
            mode = SaMode.OR, type = "admin")
    @GetMapping("/medal-grants")
    public Result<List<MedalGrantVO>> listGrants(
            @Parameter(description = "状态筛选，不传=全部") @RequestParam(required = false) Integer status,
            @Parameter(description = "志愿者筛选") @RequestParam(required = false) Long volunteerId) {
        return Result.ok(medalGrantService.listGrants(status, volunteerId));
    }

    @Operation(summary = "发起勋章发放（落待审核，须过发放审核才对志愿者生效）")
    @SaCheckPermission(value = PermissionCode.HONOR_MEDAL_GRANT, type = "admin")
    @PostMapping("/medal-grants")
    public Result<Long> grant(@Valid @RequestBody MedalGrantDTO dto) {
        return Result.ok(medalGrantService.apply(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "发放审核通过（生效，志愿者可见；附带积分同事务入账）")
    @SaCheckPermission(value = PermissionCode.HONOR_MEDAL_GRANT_AUDIT, type = "admin")
    @PostMapping("/medal-grants/{id}/approve")
    public Result<Void> approveGrant(@PathVariable Long id) {
        medalGrantService.approve(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "发放审核驳回（不生效、不发分，留痕；之后可重新发起）")
    @SaCheckPermission(value = PermissionCode.HONOR_MEDAL_GRANT_AUDIT, type = "admin")
    @PostMapping("/medal-grants/{id}/reject")
    public Result<Void> rejectGrant(@PathVariable Long id, @Valid @RequestBody RejectReasonDTO dto) {
        medalGrantService.reject(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
