package com.hengde.organization.form.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.organization.form.dto.FormDTOs;
import com.hengde.organization.form.service.FormSubmissionService;
import com.hengde.organization.form.vo.FormVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-问卷（{@code /v/organization/forms}，V4 问卷引擎批）。填写人 id 一律取登录态。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-问卷")
@RestController
@RequestMapping("/v/organization/forms")
public class FormController {

    private FormSubmissionService submissionService;

    @Autowired
    public void setSubmissionService(FormSubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @Operation(summary = "可以填写的问卷（通用 / 评优评先，收集中且在时间窗内；带「我是否已提交」）")
    @GetMapping
    public Result<PageResult<FormVOs.Form>> list(PageQuery query) {
        return Result.ok(submissionService.listAvailable(StpUtil.getLoginIdAsLong(), query));
    }

    @Operation(summary = "某个场景当前的问卷（2报名管理团队 / 3评优评先…；没有时 data 为空）")
    @GetMapping("/scenes/{scene}/current")
    public Result<FormVOs.Form> current(@PathVariable int scene) {
        return Result.ok(submissionService.currentForScene(scene, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "问卷详情（含题目；已停止的对志愿者等于不存在）")
    @GetMapping("/{id}")
    public Result<FormVOs.Form> detail(@PathVariable Long id) {
        return Result.ok(submissionService.detailForVolunteer(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "提交答卷（报名管理团队的问卷要随申请提交，不走这里）")
    @PostMapping("/{id}/submissions")
    public Result<Long> submit(@PathVariable Long id, @Valid @RequestBody FormDTOs.Submit dto) {
        return Result.ok(submissionService.submit(id, StpUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "我在这份问卷上的答卷")
    @GetMapping("/{id}/submissions/mine")
    public Result<List<FormVOs.Submission>> mine(@PathVariable Long id) {
        return Result.ok(submissionService.mySubmissions(id, StpUtil.getLoginIdAsLong()));
    }
}
