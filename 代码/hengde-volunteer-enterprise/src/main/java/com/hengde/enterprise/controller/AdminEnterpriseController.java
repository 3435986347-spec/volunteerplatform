package com.hengde.enterprise.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.excel.ExcelUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.enterprise.constant.PermissionCode;
import com.hengde.enterprise.dto.EnterpriseDTOs;
import com.hengde.enterprise.service.EnterpriseAdminService;
import com.hengde.enterprise.vo.EnterpriseVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端-爱心企业（{@code /a/enterprise/enterprises}，V4 爱心企业批，Row 15 F）。
 *
 * <p>读列表 / 详情：管理或审核权任一即可（只有审核权却看不到列表只能盲审）；导出单拆 {@code enterprise:export}（含负责人手机号）。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-爱心企业")
@RestController
@RequestMapping("/a/enterprise/enterprises")
public class AdminEnterpriseController {

    private EnterpriseAdminService adminService;

    @Autowired
    public void setAdminService(EnterpriseAdminService adminService) {
        this.adminService = adminService;
    }

    @Operation(summary = "企业列表（?status=0 为入驻审核队列，先提交的在前；?keyword= 企业名称 / 信用代码 / 登录账号片段或负责人完整手机号）")
    @SaCheckPermission(value = {PermissionCode.ENTERPRISE_MANAGE, PermissionCode.ENTERPRISE_AUDIT}, mode = SaMode.OR, type = "admin")
    @GetMapping
    public Result<PageResult<EnterpriseVOs.Account>> list(PageQuery query,
            @Parameter(description = "0待审核/1正常/2已驳回/3已暂停") @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return Result.ok(adminService.list(query, status, keyword));
    }

    @Operation(summary = "企业详情（含负责人手机号）")
    @SaCheckPermission(value = {PermissionCode.ENTERPRISE_MANAGE, PermissionCode.ENTERPRISE_AUDIT}, mode = SaMode.OR, type = "admin")
    @GetMapping("/{id}")
    public Result<EnterpriseVOs.Account> detail(@PathVariable Long id) {
        return Result.ok(adminService.detail(id));
    }

    @Operation(summary = "批量导出 xlsx（同列表筛选，最多 20000 条）")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_EXPORT, type = "admin")
    @GetMapping("/export")
    public void export(HttpServletResponse response, @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        ExcelUtil.exportTable(response, "爱心企业", "爱心企业", EnterpriseAdminService.exportHead(), adminService.exportRows(status, keyword));
    }

    @Operation(summary = "后台注册企业账号（直接为正常状态）")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_MANAGE, type = "admin")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody EnterpriseDTOs.AdminCreate dto) {
        return Result.ok(adminService.create(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "入驻审核通过")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_AUDIT, type = "admin")
    @PostMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        adminService.approve(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "入驻审核驳回（须写原因；企业改资料后可重新提交）")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_AUDIT, type = "admin")
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody EnterpriseDTOs.Reason dto) {
        adminService.reject(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "暂停（须写原因；立刻踢掉这家企业的登录）")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_MANAGE, type = "admin")
    @PostMapping("/{id}/pause")
    public Result<Void> pause(@PathVariable Long id, @Valid @RequestBody EnterpriseDTOs.Reason dto) {
        adminService.pause(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "恢复")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_MANAGE, type = "admin")
    @PostMapping("/{id}/resume")
    public Result<Void> resume(@PathVariable Long id) {
        adminService.resume(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "删除（逻辑删除，释放登录账号与信用代码；立刻踢掉登录）")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_MANAGE, type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        adminService.delete(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
