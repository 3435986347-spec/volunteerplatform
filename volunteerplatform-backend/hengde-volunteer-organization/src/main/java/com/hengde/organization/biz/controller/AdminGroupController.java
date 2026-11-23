package com.hengde.organization.biz.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.organization.biz.dto.GroupAuditDTO;
import com.hengde.organization.biz.dto.GroupLeaderDTO;
import com.hengde.organization.biz.service.GroupService;
import com.hengde.organization.biz.vo.GroupLeaderHistoryVO;
import com.hengde.organization.biz.vo.GroupMemberVO;
import com.hengde.organization.biz.vo.GroupVO;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "管理端-志愿小组")
@RestController
@RequestMapping("/a/organization/groups")
public class AdminGroupController {

    private GroupService groupService;

    @Autowired
    public void setGroupService(GroupService groupService) {
        this.groupService = groupService;
    }

    @Operation(summary = "全量小组列表")
    @SaCheckPermission(value = "org:group-manage", type = "admin")
    @GetMapping
    public Result<PageResult<GroupVO>> list(PageQuery query, @RequestParam(required = false) String keyword) {
        return Result.ok(groupService.list(query, keyword, true));
    }

    @Operation(summary = "解散小组")
    @SaCheckPermission(value = "org:group-manage", type = "admin")
    @DeleteMapping("/{id}")
    public Result<Void> dissolve(@PathVariable Long id, @RequestBody(required = false) GroupAuditDTO dto) {
        groupService.dissolve(id, dto == null ? null : dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "转移组长")
    @SaCheckPermission(value = "org:group-manage", type = "admin")
    @PutMapping("/{id}/leader")
    public Result<Void> transferLeader(@PathVariable Long id, @RequestBody @Valid GroupLeaderDTO dto) {
        groupService.transferLeader(id, dto.getVolunteerId(), StpAdminUtil.getLoginIdAsLong(), dto.getReason());
        return Result.ok();
    }

    @Operation(summary = "组长变更历史")
    @SaCheckPermission(value = "org:group-manage", type = "admin")
    @GetMapping("/{id}/leader-history")
    public Result<List<GroupLeaderHistoryVO>> leaderHistory(@PathVariable Long id) {
        return Result.ok(groupService.leaderHistory(id));
    }

    @Operation(summary = "小组成员列表（转移组长选人用）")
    @SaCheckPermission(value = "org:group-manage", type = "admin")
    @GetMapping("/{id}/members")
    public Result<List<GroupMemberVO>> members(@PathVariable Long id) {
        return Result.ok(groupService.membersForAdmin(id));
    }

    @Operation(summary = "批量导入小组数据")
    @SaCheckPermission(value = "org:group-manage", type = "admin")
    @PostMapping("/import")
    public Result<Integer> importGroups(@RequestParam("file") MultipartFile file) {
        return Result.ok(groupService.importGroups(file, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "建组申请列表")
    @SaCheckPermission(value = "org:group-audit", type = "admin")
    @GetMapping("/applications")
    public Result<PageResult<GroupVO>> applications(PageQuery query) {
        return Result.ok(groupService.applications(query));
    }

    @Operation(summary = "批准建组")
    @SaCheckPermission(value = "org:group-audit", type = "admin")
    @PostMapping("/applications/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        groupService.approveCreate(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "拒绝建组")
    @SaCheckPermission(value = "org:group-audit", type = "admin")
    @PostMapping("/applications/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @RequestBody(required = false) GroupAuditDTO dto) {
        groupService.rejectCreate(id, dto == null ? null : dto.getReason());
        return Result.ok();
    }
}
