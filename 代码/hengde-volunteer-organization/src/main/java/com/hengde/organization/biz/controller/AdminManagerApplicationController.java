package com.hengde.organization.biz.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.organization.biz.dto.RejectManagerApplicationDTO;
import com.hengde.organization.biz.service.ManagerApplicationService;
import com.hengde.organization.biz.vo.ManagerApplicationVO;
import com.hengde.organization.constant.PermissionCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端-报名管理团队审核。复用 org:manager-flag 权限点（与手动标记同域）；
 * 通过即置 volunteer.manager_flag=1（不自动授权限点，具体权限仍由超管在授权页给）。
 *
 * @author hengde
 */
@Tag(name = "管理端-报名管理团队审核")
@RestController
@RequestMapping("/a/organization/manager-applications")
public class AdminManagerApplicationController {

    private ManagerApplicationService managerApplicationService;

    @Autowired
    public void setManagerApplicationService(ManagerApplicationService managerApplicationService) {
        this.managerApplicationService = managerApplicationService;
    }

    @Operation(summary = "报名管理团队申请列表（默认待审，可传 status 覆盖）")
    @SaCheckPermission(value = PermissionCode.ORG_MANAGER_FLAG, type = "admin")
    @GetMapping
    public Result<PageResult<ManagerApplicationVO>> list(PageQuery query,
                                                         @RequestParam(required = false) Integer status) {
        return Result.ok(managerApplicationService.list(query, status));
    }

    @Operation(summary = "审核通过（置为管理团队 manager_flag=1）")
    @SaCheckPermission(value = PermissionCode.ORG_MANAGER_FLAG, type = "admin")
    @PostMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        managerApplicationService.approve(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "审核驳回")
    @SaCheckPermission(value = PermissionCode.ORG_MANAGER_FLAG, type = "admin")
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @RequestBody(required = false) RejectManagerApplicationDTO dto) {
        managerApplicationService.reject(id, dto == null ? null : dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
