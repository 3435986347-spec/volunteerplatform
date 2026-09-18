package com.hengde.organization.form.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.excel.ExcelUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.organization.constant.PermissionCode;
import com.hengde.organization.form.dto.FormDTOs;
import com.hengde.organization.form.service.FormService;
import com.hengde.organization.form.service.FormSubmissionService;
import com.hengde.organization.form.vo.FormVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
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
 * 管理端-问卷（{@code /a/organization/forms}，V4 问卷引擎批）。
 *
 * <p>两个权限点分开：{@code org:form} 管问卷本身，{@code org:form-data} 看答卷与导出（答卷里有填写人的手机号）。
 * 读问卷列表 / 详情两者任一即可——只有答卷权限却看不到问卷列表，就找不到要看的答卷。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-问卷")
@RestController
@RequestMapping("/a/organization/forms")
public class AdminFormController {

    private FormService formService;
    private FormSubmissionService submissionService;

    @Autowired
    public void setFormService(FormService formService) {
        this.formService = formService;
    }

    @Autowired
    public void setSubmissionService(FormSubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @Operation(summary = "问卷列表（带题数与答卷数）")
    @SaCheckPermission(value = {PermissionCode.ORG_FORM, PermissionCode.ORG_FORM_DATA}, mode = SaMode.OR, type = "admin")
    @GetMapping
    public Result<PageResult<FormVOs.Form>> list(PageQuery query,
            @Parameter(description = "1通用/2报名管理团队/3评优评先/4意见反馈/5投诉建议") @RequestParam(required = false) Integer scene,
            @Parameter(description = "0草稿/1收集中/2已停止") @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return Result.ok(formService.list(query, scene, status, keyword));
    }

    @Operation(summary = "新建问卷（落草稿，题目整批提交）")
    @SaCheckPermission(value = PermissionCode.ORG_FORM, type = "admin")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody FormDTOs.Save dto) {
        return Result.ok(formService.create(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "问卷详情（含题目）")
    @SaCheckPermission(value = {PermissionCode.ORG_FORM, PermissionCode.ORG_FORM_DATA}, mode = SaMode.OR, type = "admin")
    @GetMapping("/{id}")
    public Result<FormVOs.Form> detail(@PathVariable Long id) {
        return Result.ok(formService.detail(id));
    }

    @Operation(summary = "修改问卷（仅草稿；题目整批替换）")
    @SaCheckPermission(value = PermissionCode.ORG_FORM, type = "admin")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody FormDTOs.Save dto) {
        formService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除问卷（仅草稿）")
    @SaCheckPermission(value = PermissionCode.ORG_FORM, type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        formService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "发布（草稿 → 收集中；报名管理团队等场景同时只能有一份收集中）")
    @SaCheckPermission(value = PermissionCode.ORG_FORM, type = "admin")
    @PostMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable Long id) {
        formService.publish(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "停止收集（已收到的答卷不受影响）")
    @SaCheckPermission(value = PermissionCode.ORG_FORM, type = "admin")
    @PostMapping("/{id}/close")
    public Result<Void> close(@PathVariable Long id) {
        formService.close(id);
        return Result.ok();
    }

    @Operation(summary = "复制成一份新草稿（发布后要改题走这里）")
    @SaCheckPermission(value = PermissionCode.ORG_FORM, type = "admin")
    @PostMapping("/{id}/copy")
    public Result<Long> copy(@PathVariable Long id) {
        return Result.ok(formService.copy(id, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "答卷列表（逐题答案 + 填写人姓名电话）")
    @SaCheckPermission(value = PermissionCode.ORG_FORM_DATA, type = "admin")
    @GetMapping("/{id}/submissions")
    public Result<PageResult<FormVOs.Submission>> submissions(@PathVariable Long id, PageQuery query) {
        return Result.ok(submissionService.listForAdmin(id, query));
    }

    @Operation(summary = "导出答卷 Excel（每份答卷一行、每道题一列）")
    @SaCheckPermission(value = PermissionCode.ORG_FORM_DATA, type = "admin")
    @GetMapping("/{id}/submissions/export")
    public void export(@PathVariable Long id, HttpServletResponse response) {
        FormSubmissionService.ExportTable t = submissionService.exportTable(id);
        ExcelUtil.exportTable(response, "问卷答卷-" + t.title(), "答卷", t.head(), t.rows());
    }

    @Operation(summary = "答卷详情")
    @SaCheckPermission(value = PermissionCode.ORG_FORM_DATA, type = "admin")
    @GetMapping("/submissions/{submissionId}")
    public Result<FormVOs.Submission> submission(@PathVariable Long submissionId) {
        return Result.ok(submissionService.detailForAdmin(submissionId));
    }
}
