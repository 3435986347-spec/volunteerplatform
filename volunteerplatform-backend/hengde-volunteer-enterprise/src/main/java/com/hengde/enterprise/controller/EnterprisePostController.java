package com.hengde.enterprise.controller;

import com.hengde.auth.config.StpEnterpriseUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.enterprise.entity.EnterpriseAccount;
import com.hengde.enterprise.service.EnterpriseQueryService;
import com.hengde.enterprise.service.EnterpriseReviewService;
import com.hengde.enterprise.vo.EnterpriseReviewVO;
import com.hengde.social.dto.SocialDTOs;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业端-发帖与收到的评价（V4 爱心企业批·社区段，Row 15 F / Row 74）。企业 id 取登录态，要审核通过。
 *
 * <p>企业帖与志愿者帖同一张表、同一套先发后审与关键词风控；企业没有关注关系，所以<b>不收可见性</b>（一律公开）。</p>
 *
 * @author hengde
 */
@Tag(name = "企业端-发帖与评价")
@RestController
@RequestMapping("/e")
public class EnterprisePostController {

    private SocialPostService postService;
    private EnterpriseReviewService reviewService;
    private EnterpriseQueryService enterpriseQueryService;

    @Autowired
    public void setPostService(SocialPostService postService) {
        this.postService = postService;
    }

    @Autowired
    public void setReviewService(EnterpriseReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Autowired
    public void setEnterpriseQueryService(EnterpriseQueryService enterpriseQueryService) {
        this.enterpriseQueryService = enterpriseQueryService;
    }

    private static Long me() {
        return StpEnterpriseUtil.getLoginIdAsLong();
    }

    @Operation(summary = "我发过的帖子（含审核中与被驳回的，带审核状态）")
    @GetMapping("/social/posts")
    public Result<PageResult<SocialVOs.Post>> posts(PageQuery query) {
        return Result.ok(postService.ownPostsOfEnterprise(me(), query));
    }

    @Operation(summary = "以企业名义发帖（正文 / 图片 ≤9 或视频 1 个 / 评论点赞开关；作者名与头像存快照）")
    @PostMapping("/social/posts")
    public Result<Long> publish(@Valid @RequestBody SocialDTOs.PostSave dto) {
        EnterpriseAccount a = enterpriseQueryService.find(me());
        if (a == null) {
            throw new BusinessException("企业账号不存在");
        }
        return Result.ok(postService.publishForEnterprise(a.getId(), a.getName(), a.getLogoUrl(), dto));
    }

    @Operation(summary = "改自己的帖子（改完重回待审核）")
    @PutMapping("/social/posts/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody SocialDTOs.PostSave dto) {
        postService.updateForEnterprise(me(), id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除自己的帖子")
    @DeleteMapping("/social/posts/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        postService.deleteForEnterprise(me(), id);
        return Result.ok();
    }

    @Operation(summary = "收到的赞助商评价（含被后台屏蔽的，标出来）")
    @GetMapping("/enterprise/reviews")
    public Result<PageResult<EnterpriseReviewVO>> reviews(PageQuery query) {
        return Result.ok(reviewService.listForEnterprise(me(), query));
    }
}
