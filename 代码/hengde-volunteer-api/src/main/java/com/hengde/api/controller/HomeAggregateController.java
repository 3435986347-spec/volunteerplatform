package com.hengde.api.controller;

import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页聚合接口：轮播图（publicity）+ 数据看板（data）+ 推荐活动（activity）
 * 各领域依赖注入后在此聚合，避免小程序发起多次请求
 */
@Tag(name = "首页聚合")
@RestController
@RequestMapping("/v/home")
public class HomeAggregateController {

    // TODO: 注入 BannerService（publicity）、DashboardService（data）、ActivityService（activity）

    @Operation(summary = "首页聚合数据")
    @GetMapping
    public Result<Void> home() {
        // TODO: 聚合并返回 HomeVO（轮播图列表、统计数字、推荐活动列表）
        return Result.ok();
    }
}
