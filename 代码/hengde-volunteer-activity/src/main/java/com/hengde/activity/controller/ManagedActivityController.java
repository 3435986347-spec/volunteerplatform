package com.hengde.activity.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.activity.dto.BulkCheckOutDTO;
import com.hengde.activity.dto.MarkAttendanceDTO;
import com.hengde.activity.dto.ViolationDTO;
import com.hengde.activity.service.ActivityLeaderService;
import com.hengde.activity.service.AttendanceService;
import com.hengde.activity.vo.ManagedActivityDetailVO;
import com.hengde.activity.vo.ManagedActivityVO;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-活动现场负责人。仅本活动「志愿者负责人」可操作（逐入口经
 * {@link ActivityLeaderService#requireVolunteerLeader} 校验）；管理团队负责人走 {@code /a/activity} 对应动作。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-活动负责人")
@RestController
@RequestMapping("/v/activity/managed-activities")
public class ManagedActivityController {

    private AttendanceService attendanceService;
    private ActivityLeaderService activityLeaderService;

    @Autowired
    public void setAttendanceService(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @Autowired
    public void setActivityLeaderService(ActivityLeaderService activityLeaderService) {
        this.activityLeaderService = activityLeaderService;
    }

    @Operation(summary = "我负责的活动场次")
    @GetMapping
    public Result<List<ManagedActivityVO>> myLed() {
        return Result.ok(attendanceService.myLedActivities(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "负责的活动详情（志愿者名单+考勤）")
    @GetMapping("/{id}")
    public Result<ManagedActivityDetailVO> detail(@PathVariable Long id) {
        activityLeaderService.requireVolunteerLeader(id, StpUtil.getLoginIdAsLong());
        return Result.ok(attendanceService.leaderDetail(id));
    }

    @Operation(summary = "点击活动开始")
    @PostMapping("/{id}/start")
    public Result<Void> start(@PathVariable Long id) {
        Long vid = StpUtil.getLoginIdAsLong();
        activityLeaderService.requireVolunteerLeader(id, vid);
        attendanceService.startActivity(id, vid);
        return Result.ok();
    }

    @Operation(summary = "点击活动结束")
    @PostMapping("/{id}/finish")
    public Result<Void> finish(@PathVariable Long id) {
        Long vid = StpUtil.getLoginIdAsLong();
        activityLeaderService.requireVolunteerLeader(id, vid);
        attendanceService.finishActivity(id, vid);
        return Result.ok();
    }

    @Operation(summary = "统一签退（全部或指定志愿者）")
    @PostMapping("/{id}/check-outs")
    public Result<Integer> checkOut(@PathVariable Long id, @RequestBody(required = false) BulkCheckOutDTO dto) {
        Long vid = StpUtil.getLoginIdAsLong();
        activityLeaderService.requireVolunteerLeader(id, vid);
        List<Long> ids = dto == null ? null : dto.getVolunteerIds();
        return Result.ok(attendanceService.bulkCheckOut(id, ids, vid));
    }

    @Operation(summary = "标记到位状态（正常/请假/迟到/缺席）")
    @PatchMapping("/{id}/attendances/{volunteerId}")
    public Result<Void> markAttendance(@PathVariable Long id, @PathVariable Long volunteerId,
                                       @RequestBody @Valid MarkAttendanceDTO dto) {
        Long vid = StpUtil.getLoginIdAsLong();
        activityLeaderService.requireVolunteerLeader(id, vid);
        attendanceService.markAttendStatus(id, volunteerId, dto.getAttendStatus(), vid);
        return Result.ok();
    }

    @Operation(summary = "记录违规")
    @PostMapping("/{id}/attendances/{volunteerId}/violations")
    public Result<Long> recordViolation(@PathVariable Long id, @PathVariable Long volunteerId,
                                        @RequestBody @Valid ViolationDTO dto) {
        Long vid = StpUtil.getLoginIdAsLong();
        activityLeaderService.requireVolunteerLeader(id, vid);
        return Result.ok(attendanceService.recordViolation(id, volunteerId, dto.getViolationType(),
                dto.getDescription(), vid));
    }
}
