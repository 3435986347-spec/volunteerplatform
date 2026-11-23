package com.hengde.data.controller;

import com.hengde.common.result.Result;
import com.hengde.data.service.DashboardService;
import com.hengde.data.vo.DashboardVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-首页数据看板（{@code /v/data/dashboard}）。与管理端概览共用同一组聚合数字。
 *
 * <p>鉴权=需志愿者登录（{@code /v/**} 路由过滤器拦截）。纯聚合数字，无敏感明细。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-数据看板")
@RestController
@RequestMapping("/v/data")
public class VolunteerDashboardController {

    private DashboardService dashboardService;

    @Autowired
    public void setDashboardService(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "首页数据看板（注册人数/场次/时长/参与人次/管理团队/分队数）")
    @GetMapping("/dashboard")
    public Result<DashboardVO> dashboard() {
        return Result.ok(dashboardService.overview());
    }
}
