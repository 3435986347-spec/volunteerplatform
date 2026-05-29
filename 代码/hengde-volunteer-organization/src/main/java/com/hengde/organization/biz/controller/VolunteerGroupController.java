package com.hengde.organization.biz.controller;

import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.organization.biz.dto.GroupCreateDTO;
import com.hengde.organization.biz.service.GroupService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "志愿者端-志愿小组")
@RestController
@RequestMapping("/v/organization/groups")
public class VolunteerGroupController {

    private GroupService groupService;

    @Autowired
    public void setGroupService(GroupService groupService) {
        this.groupService = groupService;
    }

    @Operation(summary = "小组列表")
    @GetMapping
    public Result<PageResult<GroupVO>> list(PageQuery query, @RequestParam(required = false) String keyword) {
        return Result.ok(groupService.list(query, keyword, false));
    }

    @Operation(summary = "小组详情")
    @GetMapping("/{id}")
    public Result<GroupVO> detail(@PathVariable Long id) {
        return Result.ok(groupService.detail(id));
    }

    @Operation(summary = "发起新小组")
    @PostMapping
    public Result<Long> create(@RequestBody @Valid GroupCreateDTO dto) {
        return Result.ok(groupService.create(dto));
    }

    @Operation(summary = "申请加入小组")
    @PostMapping("/{id}/join")
    public Result<Void> join(@PathVariable Long id) {
        groupService.join(id);
        return Result.ok();
    }

    @Operation(summary = "退出小组")
    @PostMapping("/{id}/leave")
    public Result<Void> leave(@PathVariable Long id) {
        groupService.leave(id);
        return Result.ok();
    }

    @Operation(summary = "小组成员列表")
    @GetMapping("/{id}/members")
    public Result<List<GroupMemberVO>> members(@PathVariable Long id) {
        return Result.ok(groupService.members(id));
    }

    @Operation(summary = "负责人批准加入申请")
    @PostMapping("/{id}/members/{memberId}/approve")
    public Result<Void> approveMember(@PathVariable Long id, @PathVariable Long memberId) {
        groupService.approveMember(id, memberId);
        return Result.ok();
    }

    @Operation(summary = "负责人拒绝加入申请")
    @PostMapping("/{id}/members/{memberId}/reject")
    public Result<Void> rejectMember(@PathVariable Long id, @PathVariable Long memberId) {
        groupService.rejectMember(id, memberId);
        return Result.ok();
    }

    @Operation(summary = "负责人/管理员移除成员")
    @DeleteMapping("/{id}/members/{memberId}")
    public Result<Void> removeMember(@PathVariable Long id, @PathVariable Long memberId) {
        groupService.removeMember(id, memberId);
        return Result.ok();
    }

    @Operation(summary = "组长指定管理员（≤3 人）")
    @PostMapping("/{id}/members/{memberId}/admin")
    public Result<Void> setAdmin(@PathVariable Long id, @PathVariable Long memberId) {
        groupService.setAdmin(id, memberId);
        return Result.ok();
    }

    @Operation(summary = "组长取消管理员")
    @DeleteMapping("/{id}/members/{memberId}/admin")
    public Result<Void> revokeAdmin(@PathVariable Long id, @PathVariable Long memberId) {
        groupService.revokeAdmin(id, memberId);
        return Result.ok();
    }
}
