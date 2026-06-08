package com.hengde.organization.biz.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.auth.service.VolunteerAdminService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerFlagInfoView;
import com.hengde.common.result.Result;
import com.hengde.organization.constant.PermissionCode;
import com.hengde.organization.dto.AssignPermissionsDTO;
import com.hengde.organization.dto.ManagerFlagDTO;
import com.hengde.organization.service.VolunteerPermissionService;
import com.hengde.organization.vo.PermissionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端-志愿者的组织侧标记与权限。含「管理团队」标记的手动开关（V12）与志愿者端权限分配（V18）。
 *
 * <p>volunteer 表归 auth 域，标记写操作经 {@link VolunteerAdminService} 而非直接捅 mapper；权限分配经
 * {@link VolunteerPermissionService}（service 内写死<b>仅超管</b>，防自助提权，故 PUT 不挂 {@code @SaCheckPermission}）。
 * 预留的「报名管理团队」问卷审批将复用同一标记/授权通道。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-志愿者管理团队标记与权限")
@RestController
@RequestMapping("/a/organization/volunteers")
public class OrganizationVolunteerController {

    private VolunteerAdminService volunteerAdminService;
    private VolunteerPermissionService volunteerPermissionService;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setVolunteerAdminService(VolunteerAdminService volunteerAdminService) {
        this.volunteerAdminService = volunteerAdminService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setVolunteerPermissionService(VolunteerPermissionService volunteerPermissionService) {
        this.volunteerPermissionService = volunteerPermissionService;
    }

    @Operation(summary = "设置/取消志愿者管理团队标记")
    @SaCheckPermission(value = PermissionCode.ORG_MANAGER_FLAG, type = "admin")
    @PutMapping("/{id}/manager-flag")
    public Result<Void> setManagerFlag(@PathVariable Long id, @RequestBody @Valid ManagerFlagDTO dto) {
        volunteerAdminService.setManagerFlag(id, dto.getFlag(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "志愿者标记/授权页基础信息（姓名+管理团队标记+是否实名）")
    @SaCheckPermission(value = PermissionCode.ORG_MANAGER_FLAG, type = "admin")
    @GetMapping("/{id}/flag-info")
    public Result<VolunteerFlagInfoView> flagInfo(@PathVariable Long id) {
        return Result.ok(volunteerQueryService.getFlagInfo(id));
    }

    @Operation(summary = "志愿者已分配的权限点（仅超管，与授权写入口同边界；service 层校验超管）")
    @GetMapping("/{id}/permissions")
    public Result<List<PermissionVO>> permissions(@PathVariable Long id) {
        return Result.ok(volunteerPermissionService.listAssigned(id));
    }

    @Operation(summary = "全量替换志愿者权限（仅超管）")
    @PutMapping("/{id}/permissions")
    public Result<Void> assignPermissions(@PathVariable Long id, @RequestBody AssignPermissionsDTO dto) {
        volunteerPermissionService.assignPermissions(id, dto.getPermissionIds());
        return Result.ok();
    }
}
