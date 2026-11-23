package com.hengde.activity.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.activity.constant.PermissionCode;
import com.hengde.activity.dto.PointAdjustDTO;
import com.hengde.activity.service.PointService;
import com.hengde.activity.vo.PointRecordVO;
import com.hengde.activity.vo.PointSummaryVO;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 管理端-积分明细与手工调整。
 *
 * <p>查看与调整分属两个权限点：多数运营只需查明细（{@code activity:points-view}），
 * 手工加减分绕过了活动发放公式、属高危操作，单独授予（{@code activity:points-adjust}）。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-积分管理")
@RestController
@RequestMapping("/a/activity")
public class PointAdminController {

    private PointService pointService;

    @Autowired
    public void setPointService(PointService pointService) {
        this.pointService = pointService;
    }

    @Operation(summary = "查某志愿者积分总览（总积分/已使用积分/当前余额）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_POINTS_VIEW, type = "admin")
    @GetMapping("/points")
    public Result<PointSummaryVO> summary(@RequestParam Long volunteerId) {
        return Result.ok(pointService.summary(volunteerId));
    }

    @Operation(summary = "查某志愿者积分明细（分页，可搜索说明并按来源类型/时间区间筛选）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_POINTS_VIEW, type = "admin")
    @GetMapping("/points/records")
    public Result<PageResult<PointRecordVO>> records(
            @RequestParam Long volunteerId, PageQuery query,
            @RequestParam(required = false) Integer sourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) String keyword) {
        return Result.ok(pointService.pageRecords(volunteerId, query, sourceType, startTime, endTime, keyword));
    }

    @Operation(summary = "手工调整积分（可正可负，须填原因与幂等键 requestId，落操作人审计）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_POINTS_ADJUST, type = "admin")
    @PostMapping("/points/adjust")
    public Result<Integer> adjust(@RequestBody @Valid PointAdjustDTO dto) {
        return Result.ok(pointService.adjust(dto, StpAdminUtil.getLoginIdAsLong()));
    }
}
