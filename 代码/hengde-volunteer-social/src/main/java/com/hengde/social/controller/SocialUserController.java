package com.hengde.social.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.social.dto.SocialDTOs;
import com.hengde.social.service.SocialCommentService;
import com.hengde.social.service.SocialPostService;
import com.hengde.social.service.SocialUserService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-社区主页 / 关注 / 设置（V4 社区核心批，Row 23「TA的主页」「自己主页」「首页可以设置」）。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-社区主页")
@RestController
@RequestMapping("/v/social")
public class SocialUserController {

    private SocialUserService userService;
    private SocialPostService postService;
    private SocialCommentService commentService;

    @Autowired
    public void setUserService(SocialUserService userService) {
        this.userService = userService;
    }

    @Autowired
    public void setPostService(SocialPostService postService) {
        this.postService = postService;
    }

    @Autowired
    public void setCommentService(SocialCommentService commentService) {
        this.commentService = commentService;
    }

    @Operation(summary = "主页（头像 / 昵称 / 勋章 / 注册时间 / 发帖量 / 粉丝量 / 关注量 / 点赞量 / 备注 / 关系）")
    @GetMapping("/users/{id}")
    public Result<SocialVOs.Profile> profile(@PathVariable Long id) {
        return Result.ok(userService.profile(me(), id));
    }

    @Operation(summary = "TA 的帖子（只列我看得到的）")
    @GetMapping("/users/{id}/posts")
    public Result<PageResult<SocialVOs.Post>> posts(@PathVariable Long id, PageQuery query) {
        return Result.ok(postService.userPosts(me(), id, query));
    }

    @Operation(summary = "TA 的评论（只列我看得到的帖子下的）")
    @GetMapping("/users/{id}/comments")
    public Result<PageResult<SocialVOs.Comment>> comments(@PathVariable Long id, PageQuery query) {
        return Result.ok(commentService.userComments(me(), id, query));
    }

    @Operation(summary = "粉丝")
    @GetMapping("/users/{id}/followers")
    public Result<PageResult<SocialVOs.UserCard>> followers(@PathVariable Long id, PageQuery query) {
        return Result.ok(userService.followers(me(), id, query));
    }

    @Operation(summary = "关注的人")
    @GetMapping("/users/{id}/following")
    public Result<PageResult<SocialVOs.UserCard>> following(@PathVariable Long id, PageQuery query) {
        return Result.ok(userService.following(me(), id, query));
    }

    @Operation(summary = "关注（已关注不报错，data=false；TA 设置了禁止关注则拒绝）")
    @PostMapping("/users/{id}/follow")
    public Result<Boolean> follow(@PathVariable Long id) {
        return Result.ok(userService.follow(me(), id));
    }

    @Operation(summary = "取消关注")
    @DeleteMapping("/users/{id}/follow")
    public Result<Boolean> unfollow(@PathVariable Long id) {
        return Result.ok(userService.unfollow(me(), id));
    }

    @Operation(summary = "我的主页设置")
    @GetMapping("/settings")
    public Result<SocialVOs.Setting> mySetting() {
        return Result.ok(userService.mySetting(me()));
    }

    @Operation(summary = "保存主页设置（禁止关注 / 禁止评论 / 禁止点赞 / 备注，整份提交）")
    @PutMapping("/settings")
    public Result<Void> saveSetting(@Valid @RequestBody SocialDTOs.SettingSave dto) {
        userService.saveSetting(me(), dto);
        return Result.ok();
    }

    @Operation(summary = "我设置了「不让TA看」的人")
    @GetMapping("/blocks")
    public Result<List<SocialVOs.UserCard>> blocks() {
        return Result.ok(userService.blocks(me()));
    }

    @Operation(summary = "不让TA看（TA 看不到我的帖子）")
    @PostMapping("/blocks/{userId}")
    public Result<Void> block(@PathVariable Long userId) {
        userService.block(me(), userId);
        return Result.ok();
    }

    @Operation(summary = "取消「不让TA看」")
    @DeleteMapping("/blocks/{userId}")
    public Result<Void> unblock(@PathVariable Long userId) {
        userService.unblock(me(), userId);
        return Result.ok();
    }

    private static Long me() {
        return StpUtil.getLoginIdAsLong();
    }
}
