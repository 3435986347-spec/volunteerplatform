package com.hengde.activity.album.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hengde.activity.album.dto.AlbumDTOs;
import com.hengde.activity.album.entity.ActivityAlbumRule;
import com.hengde.activity.album.service.AlbumService;
import com.hengde.activity.album.vo.AlbumVOs;
import com.hengde.activity.constant.PermissionCode;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
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
import java.util.Map;

/**
 * 管理端-活动相册（V4 活动相册批，Row 11 D / F）。
 *
 * @author hengde
 */
@Tag(name = "管理端-活动相册")
@RestController
@RequestMapping("/a/activity")
public class AdminAlbumController {

    private AlbumService albumService;

    @Autowired
    public void setAlbumService(AlbumService albumService) {
        this.albumService = albumService;
    }

    @Operation(summary = "相册列表（?keyword= 搜标题）")
    @SaCheckPermission(value = {PermissionCode.ACTIVITY_ALBUM, PermissionCode.ACTIVITY_ALBUM_DELETE, PermissionCode.ACTIVITY_ALBUM_PHOTO_DELETE,
            PermissionCode.ACTIVITY_ALBUM_DOWNLOAD, PermissionCode.ACTIVITY_ALBUM_POINTS_AUDIT}, mode = SaMode.OR, type = "admin")
    @GetMapping("/albums")
    public Result<PageResult<AlbumVOs.Album>> list(PageQuery query, @RequestParam(required = false) String keyword) {
        return Result.ok(albumService.list(keyword, null, query));
    }

    @Operation(summary = "新增相册（给 activityId 就是那个活动的相册、已有直接返回；否则按 title 建不挂活动的）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ALBUM, type = "admin")
    @PostMapping("/albums")
    public Result<Long> create(@Valid @RequestBody AlbumDTOs.Create dto) {
        return Result.ok(albumService.create(StpAdminUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "删除相册")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ALBUM_DELETE, type = "admin")
    @DeleteMapping("/albums/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        albumService.deleteAlbum(StpAdminUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }

    @Operation(summary = "相册照片")
    @SaCheckPermission(value = {PermissionCode.ACTIVITY_ALBUM, PermissionCode.ACTIVITY_ALBUM_DELETE, PermissionCode.ACTIVITY_ALBUM_PHOTO_DELETE,
            PermissionCode.ACTIVITY_ALBUM_DOWNLOAD, PermissionCode.ACTIVITY_ALBUM_POINTS_AUDIT}, mode = SaMode.OR, type = "admin")
    @GetMapping("/albums/{id}/photos")
    public Result<PageResult<AlbumVOs.Photo>> photos(@PathVariable Long id, PageQuery query) {
        return Result.ok(albumService.photos(id, query));
    }

    @Operation(summary = "上传记录")
    @SaCheckPermission(value = {PermissionCode.ACTIVITY_ALBUM, PermissionCode.ACTIVITY_ALBUM_POINTS_AUDIT}, mode = SaMode.OR, type = "admin")
    @GetMapping("/albums/{id}/batches")
    public Result<PageResult<AlbumVOs.Batch>> batches(@PathVariable Long id, PageQuery query) {
        return Result.ok(albumService.batches(id, query));
    }

    @Operation(summary = "后台上传（不参与积分、不同步社区）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ALBUM, type = "admin")
    @PostMapping("/albums/{id}/photos")
    public Result<Long> upload(@PathVariable Long id, @Valid @RequestBody AlbumDTOs.Upload dto) {
        return Result.ok(albumService.adminUpload(StpAdminUtil.getLoginIdAsLong(), id, dto));
    }

    @Operation(summary = "删除照片")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ALBUM_PHOTO_DELETE, type = "admin")
    @DeleteMapping("/album-photos/{photoId}")
    public Result<Void> deletePhoto(@PathVariable Long photoId) {
        albumService.deletePhoto(StpAdminUtil.getLoginIdAsLong(), photoId);
        return Result.ok();
    }

    @Operation(summary = "批量下载：全部照片的地址与建议文件名")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ALBUM_DOWNLOAD, type = "admin")
    @GetMapping("/albums/{id}/download")
    public Result<List<Map<String, String>>> download(@PathVariable Long id) {
        return Result.ok(albumService.downloadList(id));
    }

    @Operation(summary = "上传积分审核队列（?status= 0 待审核（默认，先传的在前）/ 1 已通过 / 2 已驳回）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ALBUM_POINTS_AUDIT, type = "admin")
    @GetMapping("/album-batches")
    public Result<PageResult<AlbumVOs.Batch>> pointsQueue(PageQuery query, @RequestParam(required = false) Integer status) {
        return Result.ok(albumService.pointsQueue(status, query));
    }

    @Operation(summary = "通过：按这一批现在还没删的张数与规则发积分（个人每相册有上限），返回实际发了几分")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ALBUM_POINTS_AUDIT, type = "admin")
    @PostMapping("/album-batches/{id}/approve")
    public Result<Integer> approve(@PathVariable Long id) {
        return Result.ok(albumService.approvePoints(StpAdminUtil.getLoginIdAsLong(), id));
    }

    @Operation(summary = "驳回（不发积分）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ALBUM_POINTS_AUDIT, type = "admin")
    @PostMapping("/album-batches/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody AlbumDTOs.Reject dto) {
        albumService.rejectPoints(StpAdminUtil.getLoginIdAsLong(), id, dto.getReason());
        return Result.ok();
    }

    @Operation(summary = "上传积分规则")
    @SaCheckPermission(value = {PermissionCode.ACTIVITY_ALBUM, PermissionCode.ACTIVITY_ALBUM_POINTS_AUDIT}, mode = SaMode.OR, type = "admin")
    @GetMapping("/album-rule")
    public Result<AlbumVOs.Rule> rule() {
        ActivityAlbumRule r = albumService.rule();
        AlbumVOs.Rule vo = new AlbumVOs.Rule();
        vo.setEnabled(Integer.valueOf(1).equals(r.getEnabled()));
        vo.setPhotosPerUnit(r.getPhotosPerUnit());
        vo.setPointsPerUnit(r.getPointsPerUnit());
        vo.setMaxPointsPerAlbum(r.getMaxPointsPerAlbum());
        return Result.ok(vo);
    }

    @Operation(summary = "修改上传积分规则（每 N 张给 M 分，每人每相册上限；只影响之后审核的批次）")
    @SaCheckPermission(value = PermissionCode.ACTIVITY_ALBUM, type = "admin")
    @PutMapping("/album-rule")
    public Result<Void> saveRule(@Valid @RequestBody AlbumDTOs.Rule dto) {
        albumService.saveRule(StpAdminUtil.getLoginIdAsLong(), dto);
        return Result.ok();
    }
}
