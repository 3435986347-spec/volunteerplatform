package com.hengde.honor.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.honor.service.MedalGrantService;
import com.hengde.honor.service.RoleModelService;
import com.hengde.honor.vo.MyMedalVO;
import com.hengde.honor.vo.RoleModelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-勋章与榜样（{@code /v/honor}）。
 *
 * <p>鉴权=<b>仅需登录</b>（由 {@code /v/**} 路由过滤器拦截）。</p>
 *
 * <p><b>志愿者 id 一律取自登录态、不接受入参</b>——与积分中心同一纪律，避免越权查他人勋章。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-勋章与榜样")
@RestController
@RequestMapping("/v/honor")
public class MedalController {

    private MedalGrantService medalGrantService;
    private RoleModelService roleModelService;

    @Autowired
    public void setMedalGrantService(MedalGrantService medalGrantService) {
        this.medalGrantService = medalGrantService;
    }

    @Autowired
    public void setRoleModelService(RoleModelService roleModelService) {
        this.roleModelService = roleModelService;
    }

    @Operation(summary = "我的勋章（已启用勋章 ∪ 本人已获得的勋章 + 是否已获得 + 获取进度；只统计已生效的发放；样式按最后一次过审版本展示）")
    @GetMapping("/medals")
    public Result<List<MyMedalVO>> myMedals() {
        return Result.ok(medalGrantService.myMedals(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "榜样列表（仅已上架；分页 + 关键词 + 个人/团队筛选 + 排序）")
    @GetMapping("/role-models")
    public Result<PageResult<RoleModelVO>> roleModels(
            PageQuery query,
            @Parameter(description = "关键词，匹配标题/副标题/简介") @RequestParam(required = false) String keyword,
            @Parameter(description = "类型 1个人/2团队，不传=全部") @RequestParam(required = false) Integer modelType,
            @Parameter(description = "排序 default按运营排序(默认)/latest按发布时间倒序")
            @RequestParam(required = false) String sort) {
        return Result.ok(roleModelService.listPublished(query, keyword, modelType, sort));
    }
}
