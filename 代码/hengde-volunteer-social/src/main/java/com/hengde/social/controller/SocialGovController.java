package com.hengde.social.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.social.dto.SocialGovDTOs;
import com.hengde.social.service.SocialInteractionFeedService;
import com.hengde.social.service.SocialReportService;
import com.hengde.social.vo.SocialGovVOs;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-社区互动与举报（V4 社区治理批）。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-社区互动与举报")
@RestController
@RequestMapping("/v/social")
public class SocialGovController {

    private SocialInteractionFeedService feedService;
    private SocialReportService reportService;

    @Autowired
    public void setFeedService(SocialInteractionFeedService feedService) {
        this.feedService = feedService;
    }

    @Autowired
    public void setReportService(SocialReportService reportService) {
        this.reportService = reportService;
    }

    @Operation(summary = "我的互动（谁赞了 / 评论了 / 回复了 / 关注了我，新的在前）")
    @GetMapping("/interactions")
    public Result<PageResult<SocialGovVOs.Interaction>> interactions(PageQuery query) {
        return Result.ok(feedService.list(me(), query));
    }

    @Operation(summary = "未读互动数")
    @GetMapping("/interactions/unread-count")
    public Result<Long> unreadCount() {
        return Result.ok(feedService.unreadCount(me()));
    }

    @Operation(summary = "全部标为已读")
    @PostMapping("/interactions/read")
    public Result<Integer> markAllRead() {
        return Result.ok(feedService.markAllRead(me()));
    }

    @Operation(summary = "删除一条互动（长按删除）")
    @DeleteMapping("/interactions/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        feedService.delete(me(), id);
        return Result.ok();
    }

    @Operation(summary = "举报帖子或评论（同一对象在处理完之前只能举报一次）")
    @PostMapping("/reports")
    public Result<Long> report(@Valid @RequestBody SocialGovDTOs.ReportSave dto) {
        return Result.ok(reportService.report(me(), dto));
    }

    private static Long me() {
        return StpUtil.getLoginIdAsLong();
    }
}
