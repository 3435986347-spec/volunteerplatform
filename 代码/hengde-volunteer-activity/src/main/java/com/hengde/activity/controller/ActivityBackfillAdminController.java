package com.hengde.activity.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.activity.constant.PermissionCode;
import com.hengde.activity.dto.BackfillAuditDTO;
import com.hengde.activity.dto.BackfillRequestDTO;
import com.hengde.activity.service.ActivityBackfillService;
import com.hengde.activity.vo.ActivityBackfillVO;
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
 * 管理端-活动补录。组织部申请（{@code activity:backfill}），部长二次审核通过/拒绝（{@code activity:backfill-audit}）；
 * 通过即落已确认考勤行（普通活动发积分、历史活动只记时长）。
 *
 * @author hengde
 */
@Tag(name = "管理端-活动补录")
@RestController
@RequestMapping("/a/activity")
public class ActivityBackfillAdminController {

    private ActivityBackfillService backfillService;

    @Autowired
    public void setBackfillService(ActivityBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Operation(summary = "组织部申请补录（搜手机号/身份证 + 时间段；待审，不立即生效）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_BACKFILL, type = "admin")
    @PostMapping("/activities/{id}/backfills")
    public Result<Long> request(@PathVariable Long id, @RequestBody @Valid BackfillRequestDTO dto) {
        return Result.ok(backfillService.requestBackfill(id, dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "补录申请列表（可按状态筛选 0待审/1通过/2拒绝）")
    @GetMapping("/backfills")
    public Result<PageResult<ActivityBackfillVO>> list(PageQuery query,
                                                       @RequestParam(required = false) Integer status) {
        return Result.ok(backfillService.list(query, status));
    }

    @Operation(summary = "部长审核通过（落已确认考勤行；普通活动发积分/历史只记时长）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_BACKFILL_AUDIT, type = "admin")
    @PostMapping("/backfills/{id}/approve")
    public Result<Void> approve(@PathVariable Long id, @RequestBody(required = false) @Valid BackfillAuditDTO dto) {
        String reason = dto == null ? null : dto.getReason();
        backfillService.approve(id, reason, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "部长审核拒绝")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_BACKFILL_AUDIT, type = "admin")
    @PostMapping("/backfills/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @RequestBody(required = false) @Valid BackfillAuditDTO dto) {
        String reason = dto == null ? null : dto.getReason();
        backfillService.reject(id, reason, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
