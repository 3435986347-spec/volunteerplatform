package com.hengde.organization.biz.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.common.result.Result;
import com.hengde.organization.biz.dto.StructureDTOs;
import com.hengde.organization.biz.service.StructureService;
import com.hengde.organization.biz.vo.StructureNodeVO;
import com.hengde.organization.constant.PermissionCode;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端-组织架构维护（{@code /a/organization/structure}，V4 组织架构维护批，Row 6「框架新增、减少，均有最高权限操作」）。
 * 全部要 {@code org:structure}（超管 {@code *} 通配）。
 *
 * @author hengde
 */
@Tag(name = "管理端-组织架构维护")
@RestController
@RequestMapping("/a/organization/structure")
public class AdminStructureController {

    private StructureService structureService;

    @Autowired
    public void setStructureService(StructureService structureService) {
        this.structureService = structureService;
    }

    @Operation(summary = "架构树（含每个节点里的人）")
    @SaCheckPermission(value = PermissionCode.ORG_STRUCTURE, type = "admin")
    @GetMapping
    public Result<List<StructureNodeVO>> tree() {
        return Result.ok(structureService.tree(true));
    }

    @Operation(summary = "新增节点（必须挂在一个现存节点下面）")
    @SaCheckPermission(value = PermissionCode.ORG_STRUCTURE, type = "admin")
    @PostMapping("/nodes")
    public Result<Long> createNode(@Valid @RequestBody StructureDTOs.NodeSave dto) {
        return Result.ok(structureService.createNode(dto));
    }

    @Operation(summary = "修改节点（名称 / 说明 / 排序 / 上级；不能挂到自己的下级下面）")
    @SaCheckPermission(value = PermissionCode.ORG_STRUCTURE, type = "admin")
    @PutMapping("/nodes/{id}")
    public Result<Void> updateNode(@PathVariable Long id, @Valid @RequestBody StructureDTOs.NodeSave dto) {
        structureService.updateNode(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除节点（根不能删；还有下级节点或人的不能删）")
    @SaCheckPermission(value = PermissionCode.ORG_STRUCTURE, type = "admin")
    @DeleteMapping("/nodes/{id}")
    public Result<Void> deleteNode(@PathVariable Long id) {
        structureService.deleteNode(id);
        return Result.ok();
    }

    @Operation(summary = "把志愿者放进节点（一个人在架构里只有一个位置）")
    @SaCheckPermission(value = PermissionCode.ORG_STRUCTURE, type = "admin")
    @PostMapping("/nodes/{id}/members")
    public Result<Long> addMember(@PathVariable Long id, @Valid @RequestBody StructureDTOs.MemberSave dto) {
        return Result.ok(structureService.addMember(id, dto));
    }

    @Operation(summary = "修改职位 / 排序 / 挪到另一个节点")
    @SaCheckPermission(value = PermissionCode.ORG_STRUCTURE, type = "admin")
    @PutMapping("/members/{memberId}")
    public Result<Void> updateMember(@PathVariable Long memberId, @Valid @RequestBody StructureDTOs.MemberUpdate dto) {
        structureService.updateMember(memberId, dto);
        return Result.ok();
    }

    @Operation(summary = "把人移出架构")
    @SaCheckPermission(value = PermissionCode.ORG_STRUCTURE, type = "admin")
    @DeleteMapping("/members/{memberId}")
    public Result<Void> removeMember(@PathVariable Long memberId) {
        structureService.removeMember(memberId);
        return Result.ok();
    }
}
