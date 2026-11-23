package com.hengde.activity.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.activity.constant.PermissionCode;
import com.hengde.activity.service.RosterService;
import com.hengde.activity.vo.RosterVO;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端-活动名单公示（V4 活动补全批，Row 13 F「由组织部的同学进行审核报名资料……公示显示时间为组织部确认名单到活动开始」）。
 * 确认 / 撤回与报名审核同一个权限点 {@code activity:enroll-audit}——确认名单就是审核报名的收尾。
 *
 * @author hengde
 */
@Tag(name = "管理端-活动名单公示")
@RestController
@RequestMapping("/a/activity/activities/{id}/roster")
public class RosterAdminController {

    private RosterService rosterService;

    @Autowired
    public void setRosterService(RosterService rosterService) {
        this.rosterService = rosterService;
    }

    @Operation(summary = "预览名单（不看公示窗口，电话全显示）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ENROLL_VIEW, type = "admin")
    @GetMapping
    public Result<RosterVO> preview(@PathVariable Long id) {
        return Result.ok(rosterService.previewForAdmin(id));
    }

    @Operation(summary = "确认名单、开始公示（已发布且还没开始的活动；已公示的再点不报错）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ENROLL_AUDIT, type = "admin")
    @PostMapping("/publish")
    public Result<Void> publish(@PathVariable Long id) {
        rosterService.publish(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "撤回公示")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ENROLL_AUDIT, type = "admin")
    @DeleteMapping("/publish")
    public Result<Void> withdraw(@PathVariable Long id) {
        rosterService.withdraw(id);
        return Result.ok();
    }
}
