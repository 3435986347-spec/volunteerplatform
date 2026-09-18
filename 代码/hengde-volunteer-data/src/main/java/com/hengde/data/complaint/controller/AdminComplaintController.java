package com.hengde.data.complaint.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.auth.service.AdminQueryService;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.data.complaint.dto.ComplaintDTOs;
import com.hengde.data.complaint.service.ComplaintService;
import com.hengde.data.complaint.vo.ComplaintVOs;
import com.hengde.data.constant.PermissionCode;
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

import java.util.List;

/**
 * 管理端-投诉建议（{@code /a/data/complaints}，V4 投诉建议批）。
 *
 * <p>全部端点「{@code data:complaint} 或 {@code data:complaint-all}」任一即可进；<b>能看哪些、能动哪些由服务层按
 * {@link ComplaintService.Scope} 判</b>——只有前者的账号只碰得到当前在本部门的工单。
 * 范围在这里按登录态算好（账号 → 部门、是否持有全部门权限点），服务层不碰 Sa-Token，便于测试。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-投诉建议")
@RestController
@RequestMapping("/a/data/complaints")
public class AdminComplaintController {

    private ComplaintService complaintService;
    private AdminQueryService adminQueryService;

    @Autowired
    public void setComplaintService(ComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    @Autowired
    public void setAdminQueryService(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    @Operation(summary = "工单列表（只有 data:complaint 的账号只看得到当前在本部门的；department 参数对它无效）")
    @SaCheckPermission(value = {PermissionCode.COMPLAINT, PermissionCode.COMPLAINT_ALL}, mode = SaMode.OR, type = "admin")
    @GetMapping
    public Result<PageResult<ComplaintVOs.Complaint>> list(PageQuery query,
            @Parameter(description = "0待受理/1处理中/2已办结") @RequestParam(required = false) Integer status,
            @Parameter(description = "1投诉/2建议") @RequestParam(required = false) Integer type,
            @RequestParam(required = false) String department,
            @Parameter(description = "编号精确或内容模糊") @RequestParam(required = false) String keyword) {
        return Result.ok(complaintService.listForAdmin(scope(), query, status, type, department, keyword));
    }

    @Operation(summary = "可流转的部门（当前有启用后台账号的部门）")
    @SaCheckPermission(value = {PermissionCode.COMPLAINT, PermissionCode.COMPLAINT_ALL}, mode = SaMode.OR, type = "admin")
    @GetMapping("/departments")
    public Result<List<String>> departments() {
        return Result.ok(complaintService.departments());
    }

    @Operation(summary = "工单详情（全部进度含流转理由与内部备注、提交人电话、问卷答卷）")
    @SaCheckPermission(value = {PermissionCode.COMPLAINT, PermissionCode.COMPLAINT_ALL}, mode = SaMode.OR, type = "admin")
    @GetMapping("/{id}")
    public Result<ComplaintVOs.Complaint> detail(@PathVariable Long id) {
        return Result.ok(complaintService.detailForAdmin(scope(), id));
    }

    @Operation(summary = "受理（待受理 → 处理中）")
    @SaCheckPermission(value = {PermissionCode.COMPLAINT, PermissionCode.COMPLAINT_ALL}, mode = SaMode.OR, type = "admin")
    @PostMapping("/{id}/accept")
    public Result<Void> accept(@PathVariable Long id) {
        complaintService.accept(scope(), id);
        return Result.ok();
    }

    @Operation(summary = "流转到其他部门（回到待受理；理由仅后台可见）")
    @SaCheckPermission(value = {PermissionCode.COMPLAINT, PermissionCode.COMPLAINT_ALL}, mode = SaMode.OR, type = "admin")
    @PostMapping("/{id}/transfer")
    public Result<Void> transfer(@PathVariable Long id, @Valid @RequestBody ComplaintDTOs.Transfer dto) {
        complaintService.transfer(scope(), id, dto.getDepartment(), dto.getReason());
        return Result.ok();
    }

    @Operation(summary = "答复并办结（发站内提示 + 短信 COMPLAINT_REPLIED）")
    @SaCheckPermission(value = {PermissionCode.COMPLAINT, PermissionCode.COMPLAINT_ALL}, mode = SaMode.OR, type = "admin")
    @PostMapping("/{id}/reply")
    public Result<Void> reply(@PathVariable Long id, @Valid @RequestBody ComplaintDTOs.Text dto) {
        complaintService.reply(scope(), id, dto.getContent());
        return Result.ok();
    }

    @Operation(summary = "内部备注（仅后台可见）")
    @SaCheckPermission(value = {PermissionCode.COMPLAINT, PermissionCode.COMPLAINT_ALL}, mode = SaMode.OR, type = "admin")
    @PostMapping("/{id}/notes")
    public Result<Void> note(@PathVariable Long id, @Valid @RequestBody ComplaintDTOs.Text dto) {
        complaintService.note(scope(), id, dto.getContent());
        return Result.ok();
    }

    private ComplaintService.Scope scope() {
        long adminId = StpAdminUtil.getLoginIdAsLong();
        return new ComplaintService.Scope(adminId, adminQueryService.departmentOf(adminId),
                StpAdminUtil.STP_LOGIC.hasPermission(PermissionCode.COMPLAINT_ALL));
    }
}
