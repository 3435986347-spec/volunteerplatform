package com.hengde.organization.biz.controller;

import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.organization.biz.dto.SquadApplyDTO;
import com.hengde.organization.biz.service.SquadService;
import com.hengde.organization.biz.vo.SquadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
