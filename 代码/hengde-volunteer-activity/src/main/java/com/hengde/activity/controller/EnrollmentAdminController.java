package com.hengde.activity.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.activity.constant.PermissionCode;
import com.hengde.activity.dto.ManualEnrollDTO;
import com.hengde.activity.dto.RejectEnrollmentDTO;
import com.hengde.activity.service.EnrollmentAdminService;
import com.hengde.activity.vo.ActivitySlotVO;
import com.hengde.activity.vo.EnrollmentAdminVO;
import com.hengde.activity.vo.EnrollmentExportRow;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.excel.ExcelUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
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

import java.util.List;

/**
 * 管理端-报名管理。鉴权走管理端 StpLogic（type=admin）+ 细粒度权限点。
 *
 * @author hengde
 */
@Tag(name = "管理端-报名管理")
@RestController
@RequestMapping("/a/activity")
public class EnrollmentAdminController {

    private EnrollmentAdminService enrollmentAdminService;

    @Autowired
    public void setEnrollmentAdminService(EnrollmentAdminService enrollmentAdminService) {
        this.enrollmentAdminService = enrollmentAdminService;
    }

    @Operation(summary = "报名列表（可按状态筛选，按报名时间升序）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ENROLL_VIEW, type = "admin")
    @GetMapping("/activities/{id}/enrollments")
    public Result<PageResult<EnrollmentAdminVO>> list(@PathVariable Long id, PageQuery query,
                                                      @RequestParam(required = false) Integer status) {
        return Result.ok(enrollmentAdminService.list(id, query, status));
    }

    @Operation(summary = "全局报名列表（跨活动，可按状态筛选，按报名时间倒序；概览待审报名计数用 size=1 取 total）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ENROLL_VIEW, type = "admin")
    @GetMapping("/enrollments")
    public Result<PageResult<EnrollmentAdminVO>> listGlobal(PageQuery query,
                                                            @RequestParam(required = false) Integer status) {
        return Result.ok(enrollmentAdminService.listGlobal(query, status));
    }

    @Operation(summary = "活动时间段列表（报名域，供手动新增报名选时间段；按 enroll-view 鉴权，不需 activity:menu）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ENROLL_VIEW, type = "admin")
    @GetMapping("/activities/{id}/enrollment-slots")
    public Result<List<ActivitySlotVO>> enrollmentSlots(@PathVariable Long id) {
        return Result.ok(enrollmentAdminService.listSlots(id));
    }

    @Operation(summary = "手动新增报名（管理员代加，越权补录）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ENROLL_ADD, type = "admin")
    @PostMapping("/activities/{id}/enrollments")
    public Result<Integer> manualEnroll(@PathVariable Long id, @RequestBody @Valid ManualEnrollDTO dto) {
        return Result.ok(enrollmentAdminService.manualEnroll(id, dto.getVolunteerId(), dto.getSlotIds(),
                StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "导出报名名单（Excel）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ENROLL_EXPORT, type = "admin")
    @GetMapping("/activities/{id}/enrollments/export")
    public void export(@PathVariable Long id, HttpServletResponse response) {
        List<EnrollmentExportRow> rows = enrollmentAdminService.exportRows(id);
        ExcelUtil.export(response, "报名名单_" + id, "报名名单", EnrollmentExportRow.class, rows);
    }

    @Operation(summary = "审核通过")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ENROLL_AUDIT, type = "admin")
    @PostMapping("/enrollments/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        enrollmentAdminService.approve(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "审核拒绝（body 填拒绝原因）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ENROLL_AUDIT, type = "admin")
    @PostMapping("/enrollments/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @RequestBody @Valid RejectEnrollmentDTO dto) {
        enrollmentAdminService.reject(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "删除报名记录")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ENROLL_DELETE, type = "admin")
    @DeleteMapping("/enrollments/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        enrollmentAdminService.delete(id);
        return Result.ok();
    }
}
