package com.hengde.organization.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.common.result.Result;
import com.hengde.organization.constant.PermissionCode;
import com.hengde.organization.service.PermissionService;
import com.hengde.organization.vo.PermissionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端-可分配权限点目录。供子账号管理界面勾选。
 *
 * @author hengde
 */
@Tag(name = "管理端-权限点")
@RestController
@RequestMapping("/a/organization/permissions")
public class PermissionController {

    private PermissionService permissionService;

    @Autowired
    public void setPermissionService(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Operation(summary = "系统全量可分配权限列表")
    @SaCheckPermission(value = PermissionCode.ORG_SUB_ACCOUNT, type = "admin")
    @GetMapping
    public Result<List<PermissionVO>> listAll() {
        return Result.ok(permissionService.listAll());
    }
}
