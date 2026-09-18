package com.hengde.organization.exam.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.organization.constant.PermissionCode;
import com.hengde.organization.exam.dto.ExamDTOs;
import com.hengde.organization.exam.service.ExamAttemptService;
import com.hengde.organization.exam.service.ExamPaperService;
import com.hengde.organization.exam.vo.ExamVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 管理端-临时负责人考试（V4 临时负责人考试批）：试卷 {@code /a/organization/exam-papers}（{@code org:exam}）、
 * 答卷与阅卷 {@code /a/organization/exam-attempts}（{@code org:exam-grade}）。
 *
 * <p>读试卷：出题或阅卷权任一即可（阅卷人要看标准答案）；读答卷：阅卷或临时负责人管理权任一即可（「历史考试板块」）。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-临时负责人考试")
@RestController
@RequestMapping("/a/organization")
public class AdminExamController {

    private ExamPaperService paperService;
    private ExamAttemptService attemptService;

    @Autowired
    public void setPaperService(ExamPaperService paperService) {
        this.paperService = paperService;
    }

    @Autowired
    public void setAttemptService(ExamAttemptService attemptService) {
        this.attemptService = attemptService;
    }

    @Operation(summary = "试卷列表（带题数与答卷数）")
    @SaCheckPermission(value = {PermissionCode.ORG_EXAM, PermissionCode.ORG_EXAM_GRADE}, mode = SaMode.OR, type = "admin")
    @GetMapping("/exam-papers")
    public Result<PageResult<ExamVOs.Paper>> papers(PageQuery query,
            @Parameter(description = "0草稿/1开放中/2已停止") @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return Result.ok(paperService.list(query, status, keyword));
    }

    @Operation(summary = "新建试卷（落草稿，题目整批提交）")
    @SaCheckPermission(value = PermissionCode.ORG_EXAM, type = "admin")
    @PostMapping("/exam-papers")
    public Result<Long> create(@Valid @RequestBody ExamDTOs.PaperSave dto) {
        return Result.ok(paperService.create(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "试卷详情（含题目、分值与标准答案）")
    @SaCheckPermission(value = {PermissionCode.ORG_EXAM, PermissionCode.ORG_EXAM_GRADE}, mode = SaMode.OR, type = "admin")
    @GetMapping("/exam-papers/{id}")
    public Result<ExamVOs.Paper> paper(@PathVariable Long id) {
        return Result.ok(paperService.detail(id));
    }

    @Operation(summary = "修改试卷（仅草稿，题目整批替换）")
    @SaCheckPermission(value = PermissionCode.ORG_EXAM, type = "admin")
    @PutMapping("/exam-papers/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ExamDTOs.PaperSave dto) {
        paperService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除试卷（仅草稿）")
    @SaCheckPermission(value = PermissionCode.ORG_EXAM, type = "admin")
    @DeleteMapping("/exam-papers/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        paperService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "开放考试（同一时刻只能有一份开放中的试卷）")
    @SaCheckPermission(value = PermissionCode.ORG_EXAM, type = "admin")
    @PostMapping("/exam-papers/{id}/publish")
    public Result<Void> publish(@PathVariable Long id) {
        paperService.publish(id);
        return Result.ok();
    }

    @Operation(summary = "停止考试（已交的答卷照常阅卷）")
    @SaCheckPermission(value = PermissionCode.ORG_EXAM, type = "admin")
    @PostMapping("/exam-papers/{id}/close")
    public Result<Void> close(@PathVariable Long id) {
        paperService.close(id);
        return Result.ok();
    }

    @Operation(summary = "复制成新草稿（开放过的试卷要改题走这里）")
    @SaCheckPermission(value = PermissionCode.ORG_EXAM, type = "admin")
    @PostMapping("/exam-papers/{id}/copy")
    public Result<Long> copy(@PathVariable Long id) {
        return Result.ok(paperService.copy(id, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "答卷列表（status=1 为阅卷队列，先交的在前；可按试卷 / 志愿者 / 姓名手机号筛）")
    @SaCheckPermission(value = {PermissionCode.ORG_EXAM_GRADE, PermissionCode.ORG_TEMP_LEADER}, mode = SaMode.OR, type = "admin")
    @GetMapping("/exam-attempts")
    public Result<PageResult<ExamVOs.Attempt>> attempts(PageQuery query,
            @Parameter(description = "1待阅卷/2已出分") @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long paperId,
            @RequestParam(required = false) Long volunteerId,
            @RequestParam(required = false) String keyword) {
        return Result.ok(attemptService.list(query, status, paperId, volunteerId, keyword));
    }

    @Operation(summary = "答卷详情（逐题作答、逐题得分、标准答案 / 参考答案）")
    @SaCheckPermission(value = {PermissionCode.ORG_EXAM_GRADE, PermissionCode.ORG_TEMP_LEADER}, mode = SaMode.OR, type = "admin")
    @GetMapping("/exam-attempts/{id}")
    public Result<ExamVOs.Attempt> attempt(@PathVariable Long id) {
        return Result.ok(attemptService.adminDetail(id));
    }

    @Operation(summary = "阅卷：给每道主观题打分并出分（及格即获得资格）")
    @SaCheckPermission(value = PermissionCode.ORG_EXAM_GRADE, type = "admin")
    @PostMapping("/exam-attempts/{id}/grade")
    public Result<ExamVOs.Attempt> grade(@PathVariable Long id, @Valid @RequestBody ExamDTOs.Grade dto) {
        return Result.ok(attemptService.grade(id, dto, StpAdminUtil.getLoginIdAsLong()));
    }
}
