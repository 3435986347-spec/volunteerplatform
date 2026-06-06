package com.hengde.organization.biz.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.organization.biz.dto.GroupAuditDTO;
import com.hengde.organization.biz.dto.SquadDTO;
import com.hengde.organization.biz.service.SquadService;
import com.hengde.organization.biz.vo.SquadApplicationVO;
import com.hengde.organization.biz.vo.SquadVO;
import io.swagger.v3.oas.annotations.Operation;
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

@Tag(name = "管理端-归属分队")
@RestController
@RequestMapping("/a/organization/squads")
public class AdminSquadController {

    private SquadService squadService;

    @Autowired
    public void setSquadService(SquadService squadService) {
        this.squadService = squadService;
    }

    @Operation(summary = "分队列表")
    @SaCheckPermission(value = "org:squad-manage", type = "admin")
    @GetMapping
    public Result<PageResult<SquadVO>> list(PageQuery query) {
        return Result.ok(squadService.list(query, true));
    }

    @Operation(summary = "创建分队")
    @SaCheckPermission(value = "org:squad-manage", type = "admin")
    @PostMapping
    public Result<Long> create(@RequestBody @Valid SquadDTO dto) {
        return Result.ok(squadService.create(dto));
    }

    @Operation(summary = "修改分队信息")
    @SaCheckPermission(value = "org:squad-manage", type = "admin")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid SquadDTO dto) {
        squadService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除分队")
    @SaCheckPermission(value = "org:squad-manage", type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        squadService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "全局待审分队加入申请（不按分队；默认仅待审，可传 status 覆盖。概览待办用）")
    @SaCheckPermission(value = "org:squad-audit", type = "admin")
    @GetMapping("/applications")
    public Result<PageResult<SquadApplicationVO>> pendingApplications(PageQuery query,
                                                                     @RequestParam(required = false) Integer status) {
        return Result.ok(squadService.applications(query, status));
    }

    @Operation(summary = "加入申请列表（指定分队）")
    @SaCheckPermission(value = "org:squad-audit", type = "admin")
    @GetMapping("/{id}/applications")
    public Result<PageResult<SquadApplicationVO>> applications(@PathVariable Long id, PageQuery query) {
        return Result.ok(squadService.applications(id, query));
    }

    @Operation(summary = "批准加入")
    @SaCheckPermission(value = "org:squad-audit", type = "admin")
    @PostMapping("/applications/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        squadService.approveApplication(id);
        return Result.ok();
    }

    @Operation(summary = "拒绝加入")
    @SaCheckPermission(value = "org:squad-audit", type = "admin")
    @PostMapping("/applications/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @RequestBody(required = false) GroupAuditDTO dto) {
        squadService.rejectApplication(id, dto == null ? null : dto.getReason());
        return Result.ok();
    }
}
