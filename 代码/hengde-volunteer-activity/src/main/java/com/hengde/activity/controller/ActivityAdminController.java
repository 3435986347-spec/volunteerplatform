package com.hengde.activity.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.activity.constant.PermissionCode;
import com.hengde.activity.dto.ActivityCreateDTO;
import com.hengde.activity.dto.ActivityUpdateDTO;
import com.hengde.activity.dto.PublishRejectDTO;
import com.hengde.activity.dto.RecurringActivityDTO;
import com.hengde.activity.service.ActivityReviewService;
import com.hengde.activity.service.ActivityService;
import com.hengde.activity.vo.ActivityAdminDetailVO;
import com.hengde.activity.vo.ActivityListVO;
import com.hengde.activity.vo.ActivityReviewVO;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端-活动发布与管理。鉴权走管理端 StpLogic（type=admin）+ 细粒度权限点。
 *
 * @author hengde
 */
@Tag(name = "管理端-活动")
@RestController
@RequestMapping("/a/activity/activities")
public class ActivityAdminController {

    private ActivityService activityService;
    private ActivityReviewService activityReviewService;

    @Autowired
    public void setActivityService(ActivityService activityService) {
        this.activityService = activityService;
    }

    @Autowired
    public void setActivityReviewService(ActivityReviewService activityReviewService) {
        this.activityReviewService = activityReviewService;
    }

    @Operation(summary = "活动列表（可按状态/关键词筛选）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_MENU, type = "admin")
    @GetMapping
    public Result<PageResult<ActivityListVO>> list(PageQuery query,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) Integer status) {
        return Result.ok(activityService.listForAdmin(query, keyword, status));
    }

    @Operation(summary = "活动详情")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_MENU, type = "admin")
    @GetMapping("/{id}")
    public Result<ActivityAdminDetailVO> detail(@PathVariable Long id) {
        return Result.ok(activityService.detailForAdmin(id));
    }

    @Operation(summary = "活动发布审核列表（带提交人；status 默认 4 待审核，可传 5 看已驳回）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_PUBLISH_AUDIT, type = "admin")
    @GetMapping("/pending-reviews")
    public Result<PageResult<ActivityReviewVO>> pendingReviews(PageQuery query,
                                                              @RequestParam(required = false) Integer status) {
        return Result.ok(activityReviewService.reviews(query, status));
    }

    @Operation(summary = "待审核/驳回活动详情（完整字段，供审核者决定通过/驳回，无需 activity:menu）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_PUBLISH_AUDIT, type = "admin")
    @GetMapping("/{id}/review-detail")
    public Result<ActivityAdminDetailVO> reviewDetail(@PathVariable Long id) {
        return Result.ok(activityService.reviewDetail(id));
    }

    @Operation(summary = "发布审核通过（小程序提交的活动上线）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_PUBLISH_AUDIT, type = "admin")
    @PostMapping("/{id}/publish-approve")
    public Result<Void> publishApprove(@PathVariable Long id) {
        activityReviewService.approve(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "发布审核驳回（body 可填原因）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_PUBLISH_AUDIT, type = "admin")
    @PostMapping("/{id}/publish-reject")
    public Result<Void> publishReject(@PathVariable Long id,
                                      @RequestBody(required = false) @Valid PublishRejectDTO dto) {
        activityReviewService.reject(id, dto == null ? null : dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "发布活动")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_PUBLISH, type = "admin")
    @PostMapping
    public Result<Long> publish(@RequestBody @Valid ActivityCreateDTO dto) {
        return Result.ok(activityService.publish(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "固定日期周期发布（按显式日期/星期几规则批量发布多场）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_PUBLISH, type = "admin")
    @PostMapping("/recurring")
    public Result<List<Long>> publishRecurring(@RequestBody @Valid RecurringActivityDTO dto) {
        return Result.ok(activityService.publishRecurring(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "历史活动发布（补录之前未发布过的已发生活动；志愿者端不可见，仅作补录载体）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_PUBLISH, type = "admin")
    @PostMapping("/historical")
    public Result<Long> publishHistorical(@RequestBody @Valid ActivityCreateDTO dto) {
        return Result.ok(activityService.publishHistorical(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "修改活动")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_EDIT, type = "admin")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid ActivityUpdateDTO dto) {
        activityService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除活动")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_DELETE, type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        activityService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "复制活动")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_PUBLISH, type = "admin")
    @PostMapping("/{id}/copy")
    public Result<Long> copy(@PathVariable Long id) {
        return Result.ok(activityService.copy(id, StpAdminUtil.getLoginIdAsLong()));
    }
}
