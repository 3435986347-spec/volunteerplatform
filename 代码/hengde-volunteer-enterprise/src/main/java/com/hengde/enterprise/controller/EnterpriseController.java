package com.hengde.enterprise.controller;

import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import cn.dev33.satoken.stp.StpUtil;
import com.hengde.donate.vo.MallGoodsVO;
import com.hengde.enterprise.dto.EnterpriseReviewDTOs;
import com.hengde.enterprise.service.EnterpriseReviewService;
import com.hengde.enterprise.vo.EnterpriseReviewVO;
import com.hengde.social.service.SocialPostService;
import com.hengde.social.vo.SocialVOs;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.hengde.enterprise.service.EnterprisePublicService;
import com.hengde.enterprise.service.EnterpriseSponsorService;
import com.hengde.enterprise.vo.EnterpriseVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-爱心企业（{@code /v/enterprise/enterprises}，Row 15「编号、照片、店名、地址、电话、详情」）。只列正常的企业。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-爱心企业")
@RestController
@RequestMapping("/v/enterprise/enterprises")
public class EnterpriseController {

    private EnterprisePublicService publicService;
    private EnterpriseSponsorService sponsorService;
    private EnterpriseReviewService reviewService;
    private SocialPostService socialPostService;

    @Autowired
    public void setReviewService(EnterpriseReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Autowired
    public void setSocialPostService(SocialPostService socialPostService) {
        this.socialPostService = socialPostService;
    }

    @Autowired
    public void setSponsorService(EnterpriseSponsorService sponsorService) {
        this.sponsorService = sponsorService;
    }

    @Autowired
    public void setPublicService(EnterprisePublicService publicService) {
        this.publicService = publicService;
    }

    @Operation(summary = "爱心企业列表（按编号；?keyword= 搜店名）")
    @GetMapping
    public Result<PageResult<EnterpriseVOs.Card>> list(PageQuery query, @RequestParam(required = false) String keyword) {
        return Result.ok(publicService.list(query, keyword));
    }

    @Operation(summary = "爱心企业详情（企业主页）")
    @GetMapping("/{id}")
    public Result<EnterpriseVOs.Card> detail(@PathVariable Long id) {
        return Result.ok(publicService.detail(id));
    }

    @Operation(summary = "企业主页的帖子（企业发的帖，看得到的才列）")
    @GetMapping("/{id}/posts")
    public Result<PageResult<SocialVOs.Post>> posts(@PathVariable Long id, PageQuery query) {
        return Result.ok(socialPostService.enterprisePosts(StpUtil.getLoginIdAsLong(), id, query));
    }

    @Operation(summary = "企业主页的赞助商评价（只列正常的）")
    @GetMapping("/{id}/reviews")
    public Result<PageResult<EnterpriseReviewVO>> reviews(@PathVariable Long id, PageQuery query) {
        return Result.ok(reviewService.listPublic(id, query));
    }

    @Operation(summary = "评价赞助商（凭一张自己的、已领取的、这家企业赞助的兑换单；一单一评）")
    @PostMapping("/{id}/reviews")
    public Result<Long> review(@PathVariable Long id, @Valid @RequestBody EnterpriseReviewDTOs.Create dto) {
        return Result.ok(reviewService.create(StpUtil.getLoginIdAsLong(), id, dto));
    }

    @Operation(summary = "企业主页的赞助商品（已上架未隐藏；企业须正常）")
    @GetMapping("/{id}/goods")
    public Result<PageResult<MallGoodsVO>> goods(@PathVariable Long id, PageQuery query) {
        return Result.ok(sponsorService.goodsForVolunteer(id, query));
    }
}
