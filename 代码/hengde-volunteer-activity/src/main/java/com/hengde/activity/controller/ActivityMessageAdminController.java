package com.hengde.activity.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.activity.constant.PermissionCode;
import com.hengde.activity.service.ActivityMessageService;
import com.hengde.activity.vo.ActivityMessageVO;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端-活动留言下架（逻辑删除）。复用现场管理权限 {@code activity:manage}，不新增权限点。
 *
 * @author hengde
 */
@Tag(name = "管理端-活动留言")
@RestController
@RequestMapping("/a/activity")
public class ActivityMessageAdminController {

    private ActivityMessageService activityMessageService;

    @Autowired
    public void setActivityMessageService(ActivityMessageService activityMessageService) {
        this.activityMessageService = activityMessageService;
    }

    @Operation(summary = "活动留言列表（管理端审阅，不限活动发布状态）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_MENU, type = "admin")
    @GetMapping("/activities/{id}/messages")
    public Result<PageResult<ActivityMessageVO>> messages(@PathVariable Long id, PageQuery query) {
        return Result.ok(activityMessageService.listForAdmin(id, query));
    }

    @Operation(summary = "下架活动留言（逻辑删除）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_MANAGE, type = "admin")
    @DeleteMapping("/messages/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        activityMessageService.delete(id);
        return Result.ok();
    }
}
