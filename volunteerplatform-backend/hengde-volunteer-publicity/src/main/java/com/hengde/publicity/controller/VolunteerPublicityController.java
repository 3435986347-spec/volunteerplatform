package com.hengde.publicity.controller;

import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.publicity.service.PublicityService;
import com.hengde.publicity.vo.AnnouncementVO;
import com.hengde.publicity.vo.BannerVO;
import com.hengde.publicity.vo.PublicityFileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "志愿者端-公示")
@RestController
@RequestMapping("/v/publicity")
public class VolunteerPublicityController {

    private PublicityService publicityService;

    @Autowired
    public void setPublicityService(PublicityService publicityService) {
        this.publicityService = publicityService;
    }

    @Operation(summary = "轮播图列表")
    @GetMapping("/banners")
    public Result<PageResult<BannerVO>> banners(PageQuery query) {
        return Result.ok(publicityService.banners(query, false));
    }

    @Operation(summary = "公告列表")
    @GetMapping("/announcements")
    public Result<PageResult<AnnouncementVO>> announcements(PageQuery query) {
        return Result.ok(publicityService.announcements(query, false));
    }

    @Operation(summary = "公告详情")
    @GetMapping("/announcements/{id}")
    public Result<AnnouncementVO> announcementDetail(@PathVariable Long id) {
        return Result.ok(publicityService.announcementDetail(id));
    }

    @Operation(summary = "文件下载列表")
    @GetMapping("/files")
    public Result<PageResult<PublicityFileVO>> files(PageQuery query) {
        return Result.ok(publicityService.files(query, false));
    }
}
