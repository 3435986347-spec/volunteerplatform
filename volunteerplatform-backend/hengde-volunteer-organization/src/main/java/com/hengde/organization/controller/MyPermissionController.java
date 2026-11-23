package com.hengde.organization.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.result.Result;
import com.hengde.organization.service.VolunteerPermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-我的权限码。前端进页面后调一次，据此显示/隐藏「管理团队」入口
 * （如活动负责人页的「管理活动 / 发布活动」）。
 *
 * <p><b>仅 UX 用</b>：真正的拦截在各动作接口的 {@code @SaCheckPermission}（后端兜底，前端隐藏不等于安全）。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-我的权限")
@RestController
@RequestMapping("/v/organization")
public class MyPermissionController {

    private VolunteerPermissionService volunteerPermissionService;

    @Autowired
    public void setVolunteerPermissionService(VolunteerPermissionService volunteerPermissionService) {
        this.volunteerPermissionService = volunteerPermissionService;
    }

    @Operation(summary = "我的权限码集合")
    @GetMapping("/my-permissions")
    public Result<List<String>> myPermissions() {
        return Result.ok(volunteerPermissionService.myCodes(StpUtil.getLoginIdAsLong()));
    }
}
