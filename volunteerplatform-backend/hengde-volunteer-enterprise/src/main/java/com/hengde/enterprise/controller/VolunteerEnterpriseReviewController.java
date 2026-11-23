package com.hengde.enterprise.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.enterprise.service.EnterpriseReviewService;
import com.hengde.enterprise.vo.EnterpriseReviewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-我的赞助商评价（{@code /v/enterprise/reviews}，Row 74）。写评价在企业主页那一侧（{@code POST /v/enterprise/enterprises/{id}/reviews}）。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-我的赞助商评价")
@RestController
@RequestMapping("/v/enterprise/reviews")
public class VolunteerEnterpriseReviewController {

    private EnterpriseReviewService reviewService;

    @Autowired
    public void setReviewService(EnterpriseReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Operation(summary = "我写过的赞助商评价（含被屏蔽的，标出来）")
    @GetMapping("/mine")
    public Result<PageResult<EnterpriseReviewVO>> mine(PageQuery query) {
        return Result.ok(reviewService.listMine(StpUtil.getLoginIdAsLong(), query));
    }
}
