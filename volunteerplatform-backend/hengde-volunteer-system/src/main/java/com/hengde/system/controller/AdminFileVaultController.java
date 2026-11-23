package com.hengde.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.system.constant.PermissionCode;
import com.hengde.system.dto.SystemDTOs;
import com.hengde.system.service.FileVaultService;
import com.hengde.system.vo.SystemVOs;
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

import java.util.List;

/**
 * 管理端-文件网盘（Row 71 + Row 19）。全部挂 {@code system:file}；
 * <b>文件夹级的读写还要看授权</b>（服务层判，超管全有），改授权只有超管能做。
 *
 * @author hengde
 */
@Tag(name = "管理端-文件网盘")
@RestController
@RequestMapping("/a/system/vault")
public class AdminFileVaultController {

    private FileVaultService vaultService;

    @Autowired
    public void setVaultService(FileVaultService vaultService) {
        this.vaultService = vaultService;
    }

    private static Long me() {
        return StpAdminUtil.getLoginIdAsLong();
    }

    @Operation(summary = "文件夹树（只列我看得到的；`canWrite` 说明我能不能往里放东西）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @GetMapping("/folders")
    public Result<List<SystemVOs.Folder>> folders() {
        return Result.ok(vaultService.tree(me()));
    }

    @Operation(summary = "新建文件夹（根目录只有超管能建）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @PostMapping("/folders")
    public Result<Long> createFolder(@Valid @RequestBody SystemDTOs.FolderSave dto) {
        return Result.ok(vaultService.createFolder(dto, me()));
    }

    @Operation(summary = "改文件夹（名称 / 排序）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @PutMapping("/folders/{id}")
    public Result<Void> renameFolder(@PathVariable Long id, @Valid @RequestBody SystemDTOs.FolderSave dto) {
        vaultService.renameFolder(id, dto, me());
        return Result.ok();
    }

    @Operation(summary = "删文件夹（有下级或有文件时拒绝，不连带删）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @DeleteMapping("/folders/{id}")
    public Result<Void> deleteFolder(@PathVariable Long id) {
        vaultService.deleteFolder(id, me());
        return Result.ok();
    }

    @Operation(summary = "文件夹里的文件（?keyword= 按文件名或编号）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @GetMapping("/folders/{id}/files")
    public Result<PageResult<SystemVOs.VaultFile>> files(@PathVariable Long id,
                                                         @RequestParam(required = false) String keyword,
                                                         PageQuery query) {
        return Result.ok(vaultService.files(id, keyword, query, me()));
    }

    @Operation(summary = "登记一个文件（先经 POST /a/files/upload?dir=vault 上传；编号自动取十位号）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @PostMapping("/files")
    public Result<Long> addFile(@Valid @RequestBody SystemDTOs.FileSave dto) {
        return Result.ok(vaultService.addFile(dto, me()));
    }

    @Operation(summary = "重命名文件")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @PutMapping("/files/{id}/name")
    public Result<Void> renameFile(@PathVariable Long id, @RequestParam String name) {
        vaultService.renameFile(id, name, me());
        return Result.ok();
    }

    @Operation(summary = "移动文件到别的文件夹（两边都要有写权限）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @PutMapping("/files/{id}/folder")
    public Result<Void> moveFile(@PathVariable Long id, @RequestParam Long folderId) {
        vaultService.moveFile(id, folderId, me());
        return Result.ok();
    }

    @Operation(summary = "删除文件（逻辑删除）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @DeleteMapping("/files/{id}")
    public Result<Void> deleteFile(@PathVariable Long id) {
        vaultService.deleteFile(id, me());
        return Result.ok();
    }

    @Operation(summary = "公开到小程序「文件下载」+ 开放时间与下载开关（Row 19；撤销公开会把窗口一起清掉）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @PutMapping("/files/{id}/publish")
    public Result<Void> publish(@PathVariable Long id, @Valid @RequestBody SystemDTOs.PublishSave dto) {
        vaultService.publish(id, dto, me());
        return Result.ok();
    }

    @Operation(summary = "文件夹授权列表")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @GetMapping("/folders/{id}/grants")
    public Result<List<SystemVOs.FolderGrant>> grants(@PathVariable Long id) {
        return Result.ok(vaultService.grants(id, me()));
    }

    @Operation(summary = "给文件夹授权（**仅超管**：授给某个后台账号或一整个部门，可读或可写）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @PostMapping("/folders/{id}/grants")
    public Result<Long> grant(@PathVariable Long id, @Valid @RequestBody SystemDTOs.GrantSave dto) {
        return Result.ok(vaultService.grant(id, dto, me()));
    }

    @Operation(summary = "撤销授权（**仅超管**）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @DeleteMapping("/grants/{id}")
    public Result<Void> revokeGrant(@PathVariable Long id) {
        vaultService.revokeGrant(id, me());
        return Result.ok();
    }

    @Operation(summary = "文件的分享链接列表")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @GetMapping("/files/{id}/shares")
    public Result<List<SystemVOs.FileShare>> shares(@PathVariable Long id) {
        return Result.ok(vaultService.shares(id, me()));
    }

    @Operation(summary = "分享文件（body `requireLogin` 默认 true / `hours` 默认 168、0 = 不过期）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @PostMapping("/files/{id}/shares")
    public Result<SystemVOs.FileShare> share(@PathVariable Long id, @RequestBody(required = false) SystemDTOs.ShareSave dto) {
        return Result.ok(vaultService.share(id, dto, me()));
    }

    @Operation(summary = "撤销分享")
    @SaCheckPermission(value = PermissionCode.SYSTEM_FILE, type = "admin")
    @DeleteMapping("/shares/{id}")
    public Result<Void> revokeShare(@PathVariable Long id) {
        vaultService.revokeShare(id, me());
        return Result.ok();
    }
}
