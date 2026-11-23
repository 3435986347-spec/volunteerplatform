package com.hengde.organization.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.organization.constant.PermissionCode;
import com.hengde.organization.dto.AssignPermissionsDTO;
import com.hengde.organization.dto.ResetSubAccountPasswordDTO;
import com.hengde.organization.dto.SubAccountCreateDTO;
import com.hengde.organization.dto.SubAccountUpdateDTO;
import com.hengde.organization.service.SubAccountService;
import com.hengde.organization.vo.SubAccountVO;
import io.swagger.v3.oas.annotations.Operation;
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

/**
 * 管理端-子账号管理。常规操作需 {@code org:sub-account}；权限分配仅超管（service 内校验）。
 *
 * @author hengde
 */
@Tag(name = "管理端-子账号")
@RestController
@RequestMapping("/a/organization/sub-accounts")
public class SubAccountController {

    private SubAccountService subAccountService;

    @Autowired
    public void setSubAccountService(SubAccountService subAccountService) {
        this.subAccountService = subAccountService;
    }

    @Operation(summary = "子账号列表")
    @SaCheckPermission(value = PermissionCode.ORG_SUB_ACCOUNT, type = "admin")
    @GetMapping
    public Result<PageResult<SubAccountVO>> list(PageQuery query,
                                                 @RequestParam(required = false) String keyword) {
        return Result.ok(subAccountService.list(query, keyword));
    }

    @Operation(summary = "子账号详情（含权限列表）")
    @SaCheckPermission(value = PermissionCode.ORG_SUB_ACCOUNT, type = "admin")
    @GetMapping("/{id}")
    public Result<SubAccountVO> detail(@PathVariable Long id) {
        return Result.ok(subAccountService.detail(id));
    }

    @Operation(summary = "创建子账号")
    @SaCheckPermission(value = PermissionCode.ORG_SUB_ACCOUNT, type = "admin")
    @PostMapping
    public Result<Long> create(@RequestBody @Valid SubAccountCreateDTO dto) {
        return Result.ok(subAccountService.create(dto));
    }

    @Operation(summary = "修改子账号基本信息")
    @SaCheckPermission(value = PermissionCode.ORG_SUB_ACCOUNT, type = "admin")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid SubAccountUpdateDTO dto) {
        subAccountService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除子账号")
    @SaCheckPermission(value = PermissionCode.ORG_SUB_ACCOUNT, type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        subAccountService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "重置子账号密码")
    @SaCheckPermission(value = PermissionCode.ORG_SUB_ACCOUNT, type = "admin")
    @PostMapping("/{id}/password/reset")
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @RequestBody @Valid ResetSubAccountPasswordDTO dto) {
        subAccountService.resetPassword(id, dto.getNewPassword());
        return Result.ok();
    }

    @Operation(summary = "全量替换子账号权限（仅超管）")
    @PutMapping("/{id}/permissions")
    public Result<Void> assignPermissions(@PathVariable Long id, @RequestBody AssignPermissionsDTO dto) {
        subAccountService.assignPermissions(id, dto.getPermissionIds());
        return Result.ok();
    }
}
