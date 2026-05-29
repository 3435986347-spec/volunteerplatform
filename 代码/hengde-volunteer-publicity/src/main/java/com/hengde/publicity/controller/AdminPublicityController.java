package com.hengde.publicity.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.publicity.dto.AnnouncementDTO;
import com.hengde.publicity.dto.BannerDTO;
import com.hengde.publicity.dto.FileAccessDTO;
import com.hengde.publicity.dto.PublicityFileDTO;
import com.hengde.publicity.dto.SortDTO;
import com.hengde.publicity.service.PublicityService;
import com.hengde.publicity.vo.AnnouncementVO;
import com.hengde.publicity.vo.BannerVO;
import com.hengde.publicity.vo.PublicityFileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理端-公示")
@RestController
@RequestMapping("/a/publicity")
public class AdminPublicityController {

    private PublicityService publicityService;

    @Autowired
    public void setPublicityService(PublicityService publicityService) {
        this.publicityService = publicityService;
    }

    @Operation(summary = "轮播图列表")
    @SaCheckPermission(value = "pub:banner", type = "admin")
    @GetMapping("/banners")
    public Result<PageResult<BannerVO>> banners(PageQuery query) {
        return Result.ok(publicityService.banners(query, true));
    }

    @Operation(summary = "新增轮播图")
    @SaCheckPermission(value = "pub:banner", type = "admin")
    @PostMapping("/banners")
    public Result<Long> createBanner(@RequestBody @Valid BannerDTO dto) {
        return Result.ok(publicityService.createBanner(dto));
    }

    @Operation(summary = "修改轮播图")
    @SaCheckPermission(value = "pub:banner", type = "admin")
    @PutMapping("/banners/{id}")
    public Result<Void> updateBanner(@PathVariable Long id, @RequestBody @Valid BannerDTO dto) {
        publicityService.updateBanner(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除轮播图")
    @SaCheckPermission(value = "pub:banner", type = "admin")
    @DeleteMapping("/banners/{id}")
    public Result<Void> deleteBanner(@PathVariable Long id) {
        publicityService.deleteBanner(id);
        return Result.ok();
    }

    @Operation(summary = "调整轮播图排序")
    @SaCheckPermission(value = "pub:banner", type = "admin")
    @PatchMapping("/banners/{id}/sort")
    public Result<Void> sortBanner(@PathVariable Long id, @RequestBody @Valid SortDTO dto) {
        publicityService.sortBanner(id, dto.getSort());
        return Result.ok();
    }

    @Operation(summary = "公告列表")
    @SaCheckPermission(value = "pub:announcement", type = "admin")
    @GetMapping("/announcements")
    public Result<PageResult<AnnouncementVO>> announcements(PageQuery query) {
        return Result.ok(publicityService.announcements(query, true));
    }

    @Operation(summary = "新增公告")
    @SaCheckPermission(value = "pub:announcement", type = "admin")
    @PostMapping("/announcements")
    public Result<Long> createAnnouncement(@RequestBody @Valid AnnouncementDTO dto) {
        return Result.ok(publicityService.createAnnouncement(dto));
    }

    @Operation(summary = "修改公告")
    @SaCheckPermission(value = "pub:announcement", type = "admin")
    @PutMapping("/announcements/{id}")
    public Result<Void> updateAnnouncement(@PathVariable Long id, @RequestBody @Valid AnnouncementDTO dto) {
        publicityService.updateAnnouncement(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除公告")
    @SaCheckPermission(value = "pub:announcement", type = "admin")
    @DeleteMapping("/announcements/{id}")
    public Result<Void> deleteAnnouncement(@PathVariable Long id) {
        publicityService.deleteAnnouncement(id);
        return Result.ok();
    }

    @Operation(summary = "全量文件列表")
    @SaCheckPermission(value = "pub:file", type = "admin")
    @GetMapping("/files")
    public Result<PageResult<PublicityFileVO>> files(PageQuery query) {
        return Result.ok(publicityService.files(query, true));
    }

    @Operation(summary = "上传文件")
    @SaCheckPermission(value = "pub:file", type = "admin")
    @PostMapping("/files")
    public Result<Long> createFile(@RequestBody @Valid PublicityFileDTO dto) {
        return Result.ok(publicityService.createFile(dto));
    }

    @Operation(summary = "删除文件")
    @SaCheckPermission(value = "pub:file", type = "admin")
    @DeleteMapping("/files/{id}")
    public Result<Void> deleteFile(@PathVariable Long id) {
        publicityService.deleteFile(id);
        return Result.ok();
    }

    @Operation(summary = "开放或关闭下载")
    @SaCheckPermission(value = "pub:file", type = "admin")
    @PatchMapping("/files/{id}/access")
    public Result<Void> changeFileAccess(@PathVariable Long id, @RequestBody @Valid FileAccessDTO dto) {
        publicityService.changeFileAccess(id, dto.getDownloadable());
        return Result.ok();
    }
}
