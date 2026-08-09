package com.hengde.activity.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.activity.constant.PermissionCode;
import com.hengde.activity.dto.ViolationReviewDTO;
import com.hengde.activity.service.ViolationReviewService;
import com.hengde.activity.vo.ViolationRecordVO;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
 * 管理端-活动违规审核（{@code /a/activity/violations}）。
 *
 * <p>xlsx Row 59 后台首页待办里单列的「<b>活动违规审核</b>」；
 * Row 41 F「各类违规记录和奖励均需<b>组织部同学审核才可显示</b>」。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-活动违规审核")
@RestController
@RequestMapping("/a/activity")
public class ViolationReviewAdminController {

    private ViolationReviewService violationReviewService;

    @Autowired
    public void setViolationReviewService(ViolationReviewService violationReviewService) {
        this.violationReviewService = violationReviewService;
    }

    @Operation(summary = "违规审核队列（缺省只看待审核）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_VIOLATION_REVIEW, type = "admin")
    @GetMapping("/violations")
    public Result<PageResult<ViolationRecordVO>> list(PageQuery query,
                                                      @Parameter(description = "审核状态 0待审核/1已通过/2已驳回，缺省 0")
                                                      @RequestParam(required = false) Integer reviewStatus,
                                                      @Parameter(description = "按活动筛选")
                                                      @RequestParam(required = false) Long activityId) {
        return Result.ok(violationReviewService.list(query, reviewStatus, activityId));
    }

    @Operation(summary = "审核通过（此后该条才对志愿者可见，也才够格作为开处罚单的依据）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_VIOLATION_REVIEW, type = "admin")
    @PostMapping("/violations/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        violationReviewService.approve(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "驳回（须填原因）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_VIOLATION_REVIEW, type = "admin")
    @PostMapping("/violations/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @RequestBody @Valid ViolationReviewDTO dto) {
        violationReviewService.reject(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
