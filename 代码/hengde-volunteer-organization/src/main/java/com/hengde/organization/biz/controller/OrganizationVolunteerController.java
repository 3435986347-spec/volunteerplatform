package com.hengde.organization.biz.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.service.VolunteerAdminService;
import com.hengde.common.result.Result;
import com.hengde.organization.constant.PermissionCode;
import com.hengde.organization.dto.ManagerFlagDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端-志愿者的组织侧标记。当前仅「管理团队」标记的手动开关（V12）。
 *
 * <p>volunteer 表归 auth 域，写操作经 {@link VolunteerAdminService} 而非直接捅 mapper；本控制器只负责
 * 路由与鉴权（{@code org:manager-flag}）。预留的「报名管理团队」问卷审批（{@code /a/organization/manager-applications}）
 * 将复用同一标记通道。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-志愿者管理团队标记")
@RestController
@RequestMapping("/a/organization/volunteers")
public class OrganizationVolunteerController {

    private VolunteerAdminService volunteerAdminService;

    @Autowired
    public void setVolunteerAdminService(VolunteerAdminService volunteerAdminService) {
        this.volunteerAdminService = volunteerAdminService;
    }

    @Operation(summary = "设置/取消志愿者管理团队标记")
    @SaCheckPermission(value = PermissionCode.ORG_MANAGER_FLAG, type = "admin")
    @PutMapping("/{id}/manager-flag")
    public Result<Void> setManagerFlag(@PathVariable Long id, @RequestBody @Valid ManagerFlagDTO dto) {
        volunteerAdminService.setManagerFlag(id, dto.getFlag());
        return Result.ok();
    }
}
