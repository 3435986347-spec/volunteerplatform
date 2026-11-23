package com.hengde.enterprise.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.enterprise.constant.PermissionCode;
import com.hengde.enterprise.dto.EnterpriseReviewDTOs;
import com.hengde.enterprise.service.EnterpriseReviewService;
import com.hengde.enterprise.vo.EnterpriseReviewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端-赞助商评价（{@code /a/enterprise/reviews}，Row 74「删除、屏蔽功能」）。
 *
 * @author hengde
 */
@Tag(name = "管理端-赞助商评价")
@RestController
@RequestMapping("/a/enterprise/reviews")
public class AdminEnterpriseReviewController {

    private EnterpriseReviewService reviewService;

    @Autowired
    public void setReviewService(EnterpriseReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Operation(summary = "赞助商评价列表（?enterpriseId= / ?status= 0正常 1已屏蔽）")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_REVIEW, type = "admin")
    @GetMapping
    public Result<PageResult<EnterpriseReviewVO>> list(PageQuery query,
            @RequestParam(required = false) Long enterpriseId,
            @Parameter(description = "0正常/1已屏蔽") @RequestParam(required = false) Integer status) {
        return Result.ok(reviewService.listForAdmin(enterpriseId, status, query));
    }

    @Operation(summary = "屏蔽（对外藏起来；作者与后台仍看得到，可恢复）")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_REVIEW, type = "admin")
    @PostMapping("/{id}/hide")
    public Result<Void> hide(@PathVariable Long id, @Valid @RequestBody EnterpriseReviewDTOs.Hide dto) {
        reviewService.hide(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "恢复显示")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_REVIEW, type = "admin")
    @PostMapping("/{id}/show")
    public Result<Void> show(@PathVariable Long id) {
        reviewService.show(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "删除（逻辑删除；删掉之后这张兑换单可以重新评价）")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_REVIEW, type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        reviewService.delete(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
