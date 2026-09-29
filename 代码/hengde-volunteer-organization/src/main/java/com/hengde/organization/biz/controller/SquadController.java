package com.hengde.organization.biz.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.organization.biz.dto.GroupAuditDTO;
import com.hengde.organization.biz.dto.SquadApplyDTO;
import com.hengde.organization.biz.service.SquadService;
import com.hengde.organization.biz.vo.SquadApplicationVO;
import com.hengde.organization.biz.vo.SquadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "志愿者端-归属分队")
@RestController
@RequestMapping("/v/organization/squads")
public class SquadController {

    private SquadService squadService;

    @Autowired
    public void setSquadService(SquadService squadService) {
        this.squadService = squadService;
    }

    @Operation(summary = "分队列表")
    @GetMapping
    public Result<PageResult<SquadVO>> list(PageQuery query) {
        return Result.ok(squadService.list(query, false));
    }

    /*
     * 分队负责人审核（协会 2026-09-02：分队加入申请「分队自己审，也可以由部门审核通过」；需求表 Row 60
     * 「由上述分队进行审核是否通过」）。负责人是 volunteer_squad.leader_id 那位志愿者，只管自己负责的、启用中的分队。
     */

    @Operation(summary = "我负责的分队（分队负责人用，只列启用中的）")
    @GetMapping("/led")
    public Result<List<SquadVO>> led() {
        return Result.ok(squadService.ledSquads(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "本分队的加入申请（仅分队负责人；status 不传只看待审，0待审/1已通过/2已拒绝）")
    @GetMapping("/{id}/applications")
    public Result<PageResult<SquadApplicationVO>> applications(@PathVariable Long id,
                                                              @RequestParam(required = false) Integer status,
                                                              PageQuery query) {
        return Result.ok(squadService.applicationsForLeader(StpUtil.getLoginIdAsLong(), id, status, query));
    }

    @Operation(summary = "分队负责人批准加入（只能审自己负责的分队，不能审自己的申请）")
    @PostMapping("/applications/{applicationId}/approve")
    public Result<Void> approve(@PathVariable Long applicationId) {
        squadService.approveByLeader(StpUtil.getLoginIdAsLong(), applicationId);
        return Result.ok();
    }

    @Operation(summary = "分队负责人拒绝加入（可填原因，≤255 字）")
    @PostMapping("/applications/{applicationId}/reject")
    public Result<Void> reject(@PathVariable Long applicationId, @RequestBody(required = false) GroupAuditDTO dto) {
        squadService.rejectByLeader(StpUtil.getLoginIdAsLong(), applicationId, dto == null ? null : dto.getReason());
        return Result.ok();
    }

    @Operation(summary = "分队详情")
    @GetMapping("/{id}")
    public Result<SquadVO> detail(@PathVariable Long id) {
        return Result.ok(squadService.detail(id));
    }

    @Operation(summary = "申请加入分队")
    @PostMapping("/{id}/applications")
    public Result<Long> apply(@PathVariable Long id, @RequestBody(required = false) SquadApplyDTO dto) {
        return Result.ok(squadService.apply(id, dto));
    }
}
