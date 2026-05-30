package com.hengde.activity.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.activity.constant.PermissionCode;
import com.hengde.activity.dto.AttendanceChangeAuditDTO;
import com.hengde.activity.dto.AttendanceChangeDTO;
import com.hengde.activity.service.ActivityChangeService;
import com.hengde.activity.vo.AttendanceChangeVO;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端-考勤/积分变更二次审核。组织部申请改签到/签退/积分（{@code attendance-edit}），
 * 部长二次审核通过/拒绝（{@code attendance-audit}）；通过才应用到考勤行。
 *
 * @author hengde
 */
@Tag(name = "管理端-考勤变更二次审核")
@RestController
@RequestMapping("/a/activity")
public class AttendanceChangeAdminController {

    private ActivityChangeService activityChangeService;

    @Autowired
    public void setActivityChangeService(ActivityChangeService activityChangeService) {
        this.activityChangeService = activityChangeService;
    }

    @Operation(summary = "组织部申请改签到/签退/积分（待审，不立即生效）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ATTENDANCE_EDIT, type = "admin")
    @PostMapping("/attendances/{id}/changes")
    public Result<Long> request(@PathVariable Long id, @RequestBody @Valid AttendanceChangeDTO dto) {
        return Result.ok(activityChangeService.requestChange(id, dto.getChangeType(), dto.getNewValue(),
                dto.getReason(), StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "变更申请列表（可按状态筛选 0待审/1通过/2拒绝）")
    @GetMapping("/attendance-changes")
    public Result<PageResult<AttendanceChangeVO>> list(PageQuery query,
                                                       @RequestParam(required = false) Integer status) {
        return Result.ok(activityChangeService.list(query, status));
    }

    @Operation(summary = "部长二次审核通过（应用变更）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ATTENDANCE_AUDIT, type = "admin")
    @PostMapping("/attendance-changes/{id}/approve")
    public Result<Void> approve(@PathVariable Long id, @RequestBody(required = false) AttendanceChangeAuditDTO dto) {
        String reason = dto == null ? null : dto.getReason();
        activityChangeService.approve(id, reason, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "部长二次审核拒绝")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ATTENDANCE_AUDIT, type = "admin")
    @PostMapping("/attendance-changes/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @RequestBody(required = false) AttendanceChangeAuditDTO dto) {
        String reason = dto == null ? null : dto.getReason();
        activityChangeService.reject(id, reason, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
