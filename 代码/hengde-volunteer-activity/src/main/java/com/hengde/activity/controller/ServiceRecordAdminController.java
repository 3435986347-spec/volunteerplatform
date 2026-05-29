package com.hengde.activity.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.activity.constant.PermissionCode;
import com.hengde.activity.dto.GrantPointsDTO;
import com.hengde.activity.service.ServiceRecordService;
import com.hengde.activity.vo.ServiceRecordVO;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
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
 * 管理端-服务记录大板块 / 秘书部确认 / 积分发放。鉴权走管理端 StpLogic（type=admin）+ 细粒度权限点。
 *
 * @author hengde
 */
@Tag(name = "管理端-服务记录/秘书部确认/积分")
@RestController
@RequestMapping("/a/activity")
public class ServiceRecordAdminController {

    private ServiceRecordService serviceRecordService;

    @Autowired
    public void setServiceRecordService(ServiceRecordService serviceRecordService) {
        this.serviceRecordService = serviceRecordService;
    }

    @Operation(summary = "服务记录大板块（全员，可筛选活动/志愿者/确认状态）")
    @GetMapping("/service-records")
    public Result<PageResult<ServiceRecordVO>> board(PageQuery query,
                                                     @RequestParam(required = false) Long activityId,
                                                     @RequestParam(required = false) Long volunteerId,
                                                     @RequestParam(required = false) Integer secretaryStatus) {
        return Result.ok(serviceRecordService.board(query, activityId, volunteerId, secretaryStatus));
    }

    @Operation(summary = "待秘书部确认列表（已签退未确认）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_SERVICE_CONFIRM, type = "admin")
    @GetMapping("/service-records/pending")
    public Result<PageResult<ServiceRecordVO>> pending(PageQuery query) {
        return Result.ok(serviceRecordService.pendingConfirm(query));
    }

    @Operation(summary = "秘书部确认时长")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_SERVICE_CONFIRM, type = "admin")
    @PostMapping("/attendances/{id}/confirm")
    public Result<Void> confirm(@PathVariable Long id) {
        serviceRecordService.secretaryConfirm(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "发放积分（违规可减半/不发）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_POINTS_GRANT, type = "admin")
    @PostMapping("/attendances/{id}/points")
    public Result<Integer> grantPoints(@PathVariable Long id, @RequestBody(required = false) GrantPointsDTO dto) {
        Integer factor = dto == null ? null : dto.getPointsFactor();
        return Result.ok(serviceRecordService.grantPoints(id, factor, StpAdminUtil.getLoginIdAsLong()));
    }
}
