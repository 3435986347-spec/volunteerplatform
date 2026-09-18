package com.hengde.data.complaint.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.data.complaint.dto.ComplaintDTOs;
import com.hengde.data.complaint.service.ComplaintService;
import com.hengde.data.complaint.vo.ComplaintVOs;
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
 * 志愿者端-投诉建议（{@code /v/data/complaints}，V4 投诉建议批）。提交人 id 一律取登录态。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-投诉建议")
@RestController
@RequestMapping("/v/data/complaints")
public class ComplaintController {

    private ComplaintService complaintService;

    @Autowired
    public void setComplaintService(ComplaintService complaintService) {
        this.complaintService = complaintService;
    }

    @Operation(summary = "提交投诉建议（默认进监察部；24 小时内条数有限；协会发布了问卷时带答卷）")
    @PostMapping
    public Result<Long> submit(@Valid @RequestBody ComplaintDTOs.Submit dto) {
        return Result.ok(complaintService.submit(StpUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "我的投诉建议")
    @GetMapping("/mine")
    public Result<PageResult<ComplaintVOs.Complaint>> mine(PageQuery query) {
        return Result.ok(complaintService.mine(StpUtil.getLoginIdAsLong(), query));
    }

    @Operation(summary = "我的一条投诉建议详情（含处理进度与答复）")
    @GetMapping("/{id}")
    public Result<ComplaintVOs.Complaint> detail(@PathVariable Long id) {
        return Result.ok(complaintService.detailMine(id, StpUtil.getLoginIdAsLong()));
    }
}
