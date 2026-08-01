package com.hengde.honor.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.common.result.Result;
import com.hengde.honor.constant.PermissionCode;
import com.hengde.honor.dto.RoleModelSaveDTO;
import com.hengde.honor.service.RoleModelService;
import com.hengde.honor.vo.RoleModelVO;
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

import java.util.List;

/**
 * 管理端-榜样（{@code /a/honor/role-models}）。
 *
 * <p>与公示域的轮播图/公告同一形态：新增落下架态，上下架是独立动作，志愿者端只看已上架。
 * 榜样不走审核——「审核」是勋章那条线的要求，榜样的发布闸门就是上下架本身。</p>
 *
 * <p>图片可复用 {@code POST /a/files/upload?dir=banner}（同为公开展示图，无需新目录）。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-榜样")
@RestController
@RequestMapping("/a/honor")
public class AdminRoleModelController {

    private RoleModelService roleModelService;

    @Autowired
    public void setRoleModelService(RoleModelService roleModelService) {
        this.roleModelService = roleModelService;
    }

    @Operation(summary = "榜样列表（status 可筛选 0下架/1上架）")
    @SaCheckPermission(value = PermissionCode.HONOR_ROLE_MODEL, type = "admin")
    @GetMapping("/role-models")
    public Result<List<RoleModelVO>> list(
            @Parameter(description = "状态筛选，不传=全部") @RequestParam(required = false) Integer status) {
        return Result.ok(roleModelService.listForAdmin(status));
    }

    @Operation(summary = "新增榜样（落下架态，校对后再上架）")
    @SaCheckPermission(value = PermissionCode.HONOR_ROLE_MODEL, type = "admin")
    @PostMapping("/role-models")
    public Result<Long> create(@Valid @RequestBody RoleModelSaveDTO dto) {
        return Result.ok(roleModelService.create(dto));
    }

    @Operation(summary = "修改榜样")
    @SaCheckPermission(value = PermissionCode.HONOR_ROLE_MODEL, type = "admin")
    @PutMapping("/role-models/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody RoleModelSaveDTO dto) {
        roleModelService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "上架 / 下架（status 0下架/1上架）")
    @SaCheckPermission(value = PermissionCode.HONOR_ROLE_MODEL, type = "admin")
    @PutMapping("/role-models/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        roleModelService.changeStatus(id, status);
        return Result.ok();
    }

    @Operation(summary = "调整榜样排序")
    @SaCheckPermission(value = PermissionCode.HONOR_ROLE_MODEL, type = "admin")
    @PutMapping("/role-models/{id}/sort")
    public Result<Void> updateSort(@PathVariable Long id, @RequestParam Integer sort) {
        roleModelService.updateSort(id, sort);
        return Result.ok();
    }

    @Operation(summary = "删除榜样")
    @SaCheckPermission(value = PermissionCode.HONOR_ROLE_MODEL, type = "admin")
    @DeleteMapping("/role-models/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        roleModelService.delete(id);
        return Result.ok();
    }
}
