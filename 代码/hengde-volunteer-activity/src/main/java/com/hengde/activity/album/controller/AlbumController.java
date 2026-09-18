package com.hengde.activity.album.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.activity.album.dto.AlbumDTOs;
import com.hengde.activity.album.service.AlbumService;
import com.hengde.activity.album.vo.AlbumVOs;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-活动相册（V4 活动相册批，Row 11「查看权限：全体志愿者」「单独下载权限：全体志愿者」，Row 30 上传）。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-活动相册")
@RestController
@RequestMapping("/v/activity")
public class AlbumController {

    private AlbumService albumService;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setAlbumService(AlbumService albumService) {
        this.albumService = albumService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Operation(summary = "相册列表（?keyword= 搜标题；带张数、封面、我能不能传）")
    @GetMapping("/albums")
    public Result<PageResult<AlbumVOs.Album>> list(PageQuery query, @RequestParam(required = false) String keyword) {
        return Result.ok(albumService.list(keyword, viewer(), query));
    }

    @Operation(summary = "某个活动的相册（没有就自动建，标题「编号 活动名称」）")
    @GetMapping("/activities/{activityId}/album")
    public Result<AlbumVOs.Album> ofActivity(@PathVariable Long activityId) {
        Long me = viewer();
        return Result.ok(albumService.detail(albumService.albumOfActivity(activityId).getId(), me));
    }

    @Operation(summary = "相册详情")
    @GetMapping("/albums/{id}")
    public Result<AlbumVOs.Album> detail(@PathVariable Long id) {
        return Result.ok(albumService.detail(id, viewer()));
    }

    @Operation(summary = "相册照片（全部预览，新的在前）")
    @GetMapping("/albums/{id}/photos")
    public Result<PageResult<AlbumVOs.Photo>> photos(@PathVariable Long id, PageQuery query) {
        viewer();
        return Result.ok(albumService.photos(id, query));
    }

    @Operation(summary = "上传记录（谁传的、传了多少张）")
    @GetMapping("/albums/{id}/batches")
    public Result<PageResult<AlbumVOs.Batch>> batches(@PathVariable Long id, PageQuery query) {
        viewer();
        return Result.ok(albumService.batches(id, query));
    }

    @Operation(summary = "上传一批照片（本活动志愿者 / 负责人 / 管理团队；syncSocial 默认 true 同步到交流平台；上传积分需审核）")
    @PostMapping("/albums/{id}/photos")
    public Result<Long> upload(@PathVariable Long id, @Valid @RequestBody AlbumDTOs.Upload dto) {
        return Result.ok(albumService.upload(StpUtil.getLoginIdAsLong(), id, dto));
    }

    /** 看相册要已实名（Row 11「全体志愿者」）。 */
    private Long viewer() {
        Long me = StpUtil.getLoginIdAsLong();
        if (!volunteerQueryService.filterActiveRegistered(List.of(me)).contains(me)) {
            throw new BusinessException("实名注册后才能看活动相册");
        }
        return me;
    }
}
