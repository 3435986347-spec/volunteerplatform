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
 * 管理端-数据看板（{@code /a/data/dashboard}）。
 *
 * <p>鉴权按 url 文档=<b>仅需管理端登录</b>（由 {@code /a/**} 路由过滤器拦截，纯聚合数字、不挂权限点）；
 * {@code data:dashboard} 权限点用于前端控制「数据看板」菜单可见性，不强制于本接口。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-数据看板")
@RestController
@RequestMapping("/a/data")
public class AdminDashboardController {

    private DashboardService dashboardService;

    @Autowired
    public void setDashboardService(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "后台数据概览（注册人数/场次/时长/参与人次/管理团队/分队数）")
    @GetMapping("/dashboard")
    public Result<DashboardVO> dashboard() {
        return Result.ok(dashboardService.overview());
    }
}
