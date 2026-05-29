package com.hengde.activity.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.activity.constant.PermissionCode;
import com.hengde.activity.dto.AssignLeaderDTO;
import com.hengde.activity.dto.BulkCheckOutDTO;
import com.hengde.activity.dto.MarkAttendanceDTO;
import com.hengde.activity.dto.ViolationDTO;
import com.hengde.activity.service.ActivityLeaderService;
import com.hengde.activity.service.AttendanceService;
import com.hengde.activity.vo.ActivityLeaderVO;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端-活动负责人指派 + 现场管理（管理团队负责人 / 有活动管理权限的同学）。
 * 鉴权走管理端 StpLogic（type=admin）+ 细粒度权限点。
 *
 * @author hengde
 */
@Tag(name = "管理端-活动负责人/现场管理")
@RestController
@RequestMapping("/a/activity")
public class ActivityManageAdminController {

    private ActivityLeaderService activityLeaderService;
    private AttendanceService attendanceService;

    @Autowired
    public void setActivityLeaderService(ActivityLeaderService activityLeaderService) {
        this.activityLeaderService = activityLeaderService;
    }

    @Autowired
    public void setAttendanceService(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @Operation(summary = "指派活动负责人（志愿者或管理团队）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_LEADER_ASSIGN, type = "admin")
    @PostMapping("/activities/{id}/leaders")
    public Result<Long> assignLeader(@PathVariable Long id, @RequestBody @Valid AssignLeaderDTO dto) {
        return Result.ok(activityLeaderService.assign(id, dto.getLeaderType(), dto.getRefId(),
                StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "负责人列表")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_MANAGE, type = "admin")
    @GetMapping("/activities/{id}/leaders")
    public Result<List<ActivityLeaderVO>> leaders(@PathVariable Long id) {
        return Result.ok(activityLeaderService.list(id));
    }

    @Operation(summary = "取消指派")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_LEADER_ASSIGN, type = "admin")
    @DeleteMapping("/activities/{id}/leaders/{leaderId}")
    public Result<Void> removeLeader(@PathVariable Long id, @PathVariable Long leaderId) {
        activityLeaderService.remove(id, leaderId);
        return Result.ok();
    }

    @Operation(summary = "活动开始")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_MANAGE, type = "admin")
    @PostMapping("/activities/{id}/start")
    public Result<Void> start(@PathVariable Long id) {
        attendanceService.startActivity(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "活动结束")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_MANAGE, type = "admin")
    @PostMapping("/activities/{id}/finish")
    public Result<Void> finish(@PathVariable Long id) {
        attendanceService.finishActivity(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "统一签退（全部或指定志愿者）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_MANAGE, type = "admin")
    @PostMapping("/activities/{id}/check-outs")
    public Result<Integer> checkOut(@PathVariable Long id, @RequestBody(required = false) BulkCheckOutDTO dto) {
        List<Long> ids = dto == null ? null : dto.getVolunteerIds();
        return Result.ok(attendanceService.bulkCheckOut(id, ids, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "标记到位状态")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_MANAGE, type = "admin")
    @PatchMapping("/activities/{id}/attendances/{volunteerId}")
    public Result<Void> markAttendance(@PathVariable Long id, @PathVariable Long volunteerId,
                                       @RequestBody @Valid MarkAttendanceDTO dto) {
        attendanceService.markAttendStatus(id, volunteerId, dto.getAttendStatus(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "记录违规")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_MANAGE, type = "admin")
    @PostMapping("/activities/{id}/attendances/{volunteerId}/violations")
    public Result<Long> recordViolation(@PathVariable Long id, @PathVariable Long volunteerId,
                                        @RequestBody @Valid ViolationDTO dto) {
        return Result.ok(attendanceService.recordViolation(id, volunteerId, dto.getViolationType(),
                dto.getDescription(), StpAdminUtil.getLoginIdAsLong()));
    }
}
