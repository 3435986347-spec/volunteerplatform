package com.hengde.honor.controller;

import com.hengde.common.result.Result;
import com.hengde.honor.service.RankingService;
import com.hengde.honor.vo.RankingVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-排行榜（{@code /v/honor/rankings}）。
 *
 * <p>鉴权=<b>仅需登录</b>（由 {@code /v/**} 路由过滤器拦截）。榜单是给全体志愿者看的公开信息，
 * 不挂权限点；{@code honor:ranking-view} 是管理端的点，与此无关。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-排行榜")
@RestController
@RequestMapping("/v/honor")
public class RankingController {

    private RankingService rankingService;

    @Autowired
    public void setRankingService(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @Operation(summary = "排行榜（次数/时长/积分 × 月/年/总；往期读冻结快照）")
    @GetMapping("/rankings")
    public Result<RankingVO> ranking(
            @Parameter(description = "榜单 1活动次数/2活动时长/3积分") @RequestParam Integer rankType,
            @Parameter(description = "周期 1月/2年/3总") @RequestParam Integer periodType,
            @Parameter(description = "周期标识：月 2026-07 / 年 2026；总榜可不传")
            @RequestParam(required = false) String periodKey,
            @Parameter(description = "取前 N，默认 50，上限 100")
            @RequestParam(required = false) Integer limit) {
        return Result.ok(rankingService.ranking(rankType, periodType, periodKey, limit));
    }
}
