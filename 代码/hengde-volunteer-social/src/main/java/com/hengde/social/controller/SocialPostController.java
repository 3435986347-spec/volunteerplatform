package com.hengde.social.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.social.dto.SocialDTOs;
import com.hengde.social.service.SocialCommentService;
import com.hengde.social.service.SocialInteractionService;
import com.hengde.social.service.SocialPostService;
import com.hengde.social.vo.SocialVOs;
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

/**
 * 志愿者端-社区帖子（V4 社区核心批，Row 23）。看帖要已验手机号，发帖 / 评论 / 点赞要已实名（服务层判定）。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-社区")
@RestController
@RequestMapping("/v/social")
public class SocialPostController {

    private SocialPostService postService;
    private SocialInteractionService interactionService;
    private SocialCommentService commentService;

    @Autowired
    public void setPostService(SocialPostService postService) {
        this.postService = postService;
    }

    @Autowired
    public void setInteractionService(SocialInteractionService interactionService) {
        this.interactionService = interactionService;
    }

    @Autowired
    public void setCommentService(SocialCommentService commentService) {
        this.commentService = commentService;
    }

    @Operation(summary = "帖子流：tab=latest 最新（默认）/ hot 最热 / following 关注 / official 官方；keyword 搜索")
    @GetMapping("/posts")
    public Result<PageResult<SocialVOs.Post>> feed(PageQuery query, @RequestParam(required = false) String tab,
                                                   @RequestParam(required = false) String keyword) {
        return Result.ok(postService.feed(me(), tab, keyword, query));
    }

    @Operation(summary = "帖子详情（查看量 +1）")
    @GetMapping("/posts/{id}")
    public Result<SocialVOs.Post> detail(@PathVariable Long id) {
        return Result.ok(postService.detail(me(), id));
    }

    @Operation(summary = "发帖（文字 / 图片 / 视频 + 可见性与评论点赞开关）")
    @PostMapping("/posts")
    public Result<Long> publish(@Valid @RequestBody SocialDTOs.PostSave dto) {
        return Result.ok(postService.publish(me(), dto));
    }

    @Operation(summary = "修改自己的帖子（改完重回待审核）")
    @PutMapping("/posts/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody SocialDTOs.PostSave dto) {
        postService.update(me(), id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除自己的帖子")
    @DeleteMapping("/posts/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        postService.delete(me(), id);
        return Result.ok();
    }

    @Operation(summary = "分享（分享量 +1）")
    @PostMapping("/posts/{id}/share")
    public Result<Void> share(@PathVariable Long id) {
        postService.share(me(), id);
        return Result.ok();
    }

    @Operation(summary = "点赞（已赞过不报错，data=false）")
    @PostMapping("/posts/{id}/like")
    public Result<Boolean> like(@PathVariable Long id) {
        return Result.ok(interactionService.like(me(), id));
    }

    @Operation(summary = "取消点赞（本来没赞不报错，data=false）")
    @DeleteMapping("/posts/{id}/like")
    public Result<Boolean> unlike(@PathVariable Long id) {
        return Result.ok(interactionService.unlike(me(), id));
    }

    @Operation(summary = "帖子的评论（按时间先后）")
    @GetMapping("/posts/{id}/comments")
    public Result<PageResult<SocialVOs.Comment>> comments(@PathVariable Long id, PageQuery query) {
        return Result.ok(commentService.list(me(), id, query));
    }

    @Operation(summary = "评论 / 回复（parentId 为回复的评论）")
    @PostMapping("/posts/{id}/comments")
    public Result<Long> comment(@PathVariable Long id, @Valid @RequestBody SocialDTOs.CommentSave dto) {
        return Result.ok(commentService.comment(me(), id, dto));
    }

    @Operation(summary = "删除评论（自己的评论，或自己帖子下的评论）")
    @DeleteMapping("/comments/{commentId}")
    public Result<Void> deleteComment(@PathVariable Long commentId) {
        commentService.delete(me(), commentId);
        return Result.ok();
    }

    private static Long me() {
        return StpUtil.getLoginIdAsLong();
    }
}
