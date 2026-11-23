package com.hengde.organization.exam.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.organization.exam.dto.ExamDTOs;
import com.hengde.organization.exam.service.ExamAttemptService;
import com.hengde.organization.exam.vo.ExamVOs;
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

/**
 * 志愿者端-活动临时负责人考试（{@code /v/organization/exams}，V4 临时负责人考试批）。考生 id 一律取登录态。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-临时负责人考试")
@RestController
@RequestMapping("/v/organization/exams")
public class ExamController {

    private ExamAttemptService attemptService;

    @Autowired
    public void setAttemptService(ExamAttemptService attemptService) {
        this.attemptService = attemptService;
    }

    @Operation(summary = "我的考试：当前开放的试卷（能考时带题目、不含答案）+ 我是不是临时负责人 + 能不能考")
    @GetMapping("/current")
    public Result<ExamVOs.MyExam> current() {
        return Result.ok(attemptService.myExam(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "交卷（没有主观题的当场出分，及格即获得资格）")
    @PostMapping("/attempts")
    public Result<ExamVOs.Attempt> submit(@Valid @RequestBody ExamDTOs.Submit dto) {
        return Result.ok(attemptService.submit(StpUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "我的考试历史")
    @GetMapping("/attempts")
    public Result<PageResult<ExamVOs.Attempt>> myAttempts(PageQuery query) {
        return Result.ok(attemptService.myAttempts(StpUtil.getLoginIdAsLong(), query));
    }

    @Operation(summary = "我的一份答卷（自己的作答与总分，不含标准答案与逐题得分）")
    @GetMapping("/attempts/{id}")
    public Result<ExamVOs.Attempt> myAttempt(@PathVariable Long id) {
        return Result.ok(attemptService.myAttemptDetail(StpUtil.getLoginIdAsLong(), id));
    }
}
