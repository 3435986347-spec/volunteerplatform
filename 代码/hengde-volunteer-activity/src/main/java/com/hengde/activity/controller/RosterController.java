package com.hengde.activity.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.activity.service.RosterService;
import com.hengde.activity.vo.RosterVO;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-活动名单公示（V4 活动补全批，Row 13）。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-活动名单公示")
@RestController
@RequestMapping("/v/activity/rosters")
public class RosterController {

    private RosterService rosterService;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setRosterService(RosterService rosterService) {
        this.rosterService = rosterService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Operation(summary = "正在公示的名单（组织部确认名单后到活动开始前；按活动开始时间）")
    @GetMapping
    public Result<PageResult<RosterVO>> list(PageQuery query) {
        return Result.ok(rosterService.listForVolunteer(query, registeredViewer()));
    }

    @Operation(summary = "某个活动正在公示的名单")
    @GetMapping("/{activityId}")
    public Result<RosterVO> detail(@PathVariable Long activityId) {
        return Result.ok(rosterService.detailForVolunteer(activityId, registeredViewer()));
    }

    private boolean registeredViewer() {
        Long me = StpUtil.getLoginIdAsLong();
        return volunteerQueryService.filterActiveRegistered(List.of(me)).contains(me);
    }
}
