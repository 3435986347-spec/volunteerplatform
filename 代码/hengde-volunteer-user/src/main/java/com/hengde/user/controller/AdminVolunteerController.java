package com.hengde.user.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.common.excel.ExcelUtil;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.organization.constant.PermissionCode;
import com.hengde.user.dto.VolunteerQueryDTO;
import com.hengde.user.dto.VolunteerStatusDTO;
import com.hengde.user.dto.VolunteerUpdateDTO;
import com.hengde.user.service.AdminVolunteerService;
import com.hengde.user.vo.AdminVolunteerDetailVO;
import com.hengde.user.vo.AdminVolunteerListVO;
import com.hengde.user.vo.VolunteerExportRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端-志愿者管理（{@code /a/user/volunteers}）。列表/详情/修改/停用·恢复/删除/导出/重置密码。
 *
 * <p>鉴权按 V2 已 seed 的 {@code user:*} 细粒度权限点（菜单/数据/审核分离）：查看（含明文手机号/详情）={@code user:list}、
 * 导出={@code user:export}、停用恢复={@code user:status}、删除={@code user:delete}、重置密码={@code user:pwd-reset}；
 * 超管 {@code *} 通配放行。<b>修改实名敏感资料（{@code user:edit}）写死仅超管</b>（不在权限点表、不挂注解，service 层手写
 * {@code is_super_admin} 校验），与 organization 的同口径一致。「管理团队」标记与活动域授权在 organization 标记/授权页，不在此。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-志愿者管理")
@RestController
@RequestMapping("/a/user/volunteers")
public class AdminVolunteerController {

    private AdminVolunteerService adminVolunteerService;

    @Autowired
    public void setAdminVolunteerService(AdminVolunteerService adminVolunteerService) {
        this.adminVolunteerService = adminVolunteerService;
    }

    @Operation(summary = "志愿者列表（多条件筛选 + 分页）")
    @SaCheckPermission(value = PermissionCode.USER_LIST, type = "admin")
    @GetMapping
    public Result<PageResult<AdminVolunteerListVO>> list(VolunteerQueryDTO query) {
        return Result.ok(adminVolunteerService.list(query));
    }

    @Operation(summary = "导出志愿者名单（Excel，支持与列表相同筛选）")
    @SaCheckPermission(value = PermissionCode.USER_EXPORT, type = "admin")
    @GetMapping("/export")
    public void export(VolunteerQueryDTO query, HttpServletResponse response) {
        List<VolunteerExportRow> rows = adminVolunteerService.exportRows(query);
        ExcelUtil.export(response, "志愿者名单", "志愿者", VolunteerExportRow.class, rows);
    }

    @Operation(summary = "志愿者详情（含明文手机号/身份证尾号）")
    @SaCheckPermission(value = PermissionCode.USER_LIST, type = "admin")
    @GetMapping("/{id}")
    public Result<AdminVolunteerDetailVO> detail(@PathVariable Long id) {
        return Result.ok(adminVolunteerService.detail(id));
    }

    @Operation(summary = "修改志愿者全量信息（实名敏感字段，仅超管）")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid VolunteerUpdateDTO dto) {
        adminVolunteerService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "暂停/恢复志愿者账号（status 0正常/1禁用）")
    @SaCheckPermission(value = PermissionCode.USER_STATUS, type = "admin")
    @PatchMapping("/{id}/status")
    public Result<Void> setStatus(@PathVariable Long id, @RequestBody @Valid VolunteerStatusDTO dto) {
        adminVolunteerService.setStatus(id, dto.getStatus());
        return Result.ok();
    }

    @Operation(summary = "删除志愿者（逻辑删除）")
    @SaCheckPermission(value = PermissionCode.USER_DELETE, type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        adminVolunteerService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "重置志愿者密码（清空 password，重置后需手机号验证码重登再自设新密码）")
    @SaCheckPermission(value = PermissionCode.USER_PWD_RESET, type = "admin")
    @PostMapping("/{id}/password/reset")
    public Result<Void> resetPassword(@PathVariable Long id) {
        adminVolunteerService.resetPassword(id);
        return Result.ok();
    }
}
