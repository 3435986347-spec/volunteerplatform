package com.hengde.activity.controller;

import com.hengde.activity.service.ActivityService;
import com.hengde.activity.vo.ActivityVolunteerDetailVO;
import com.hengde.activity.vo.RecommendActivityVO;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-活动浏览。登录态由 {@code /v/**} 路由拦截统一校验，无需细粒度权限点。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-活动")
@RestController
@RequestMapping("/v/activity/activities")
public class ActivityController {

    private ActivityService activityService;

    @Autowired
    public void setActivityService(ActivityService activityService) {
        this.activityService = activityService;
    }

    @Operation(summary = "活动列表/推荐（仅已发布；有名额优先排序，带报名人数/有名额标记）")
    @GetMapping
    public Result<PageResult<RecommendActivityVO>> list(PageQuery query,
                                                        @RequestParam(required = false) String keyword) {
        return Result.ok(activityService.listForVolunteer(query, keyword));
    }

    @Operation(summary = "活动详情（含时间段/报名须知，仅已发布）")
    @GetMapping("/{id}")
    public Result<ActivityVolunteerDetailVO> detail(@PathVariable Long id) {
        return Result.ok(activityService.detailForVolunteer(id));
    }
}
