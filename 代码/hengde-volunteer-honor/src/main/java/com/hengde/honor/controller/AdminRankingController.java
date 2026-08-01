package com.hengde.honor.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.common.result.Result;
import com.hengde.honor.constant.PermissionCode;
import com.hengde.honor.service.RankingService;
import com.hengde.honor.vo.RankingVO;
import com.hengde.honor.vo.SnapshotResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端-排行榜（{@code /a/honor/rankings}）。
 *
 * @author hengde
 */
@Tag(name = "管理端-排行榜")
@RestController
@RequestMapping("/a/honor")
public class AdminRankingController {

    private RankingService rankingService;

    @Autowired
    public void setRankingService(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @Operation(summary = "排行榜（与志愿者端同一份数据）")
    @SaCheckPermission(value = PermissionCode.HONOR_RANKING_VIEW, type = "admin")
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

    /**
     * 补跑快照。
     *
     * <p>两种用途：① 本功能上线前的历史月份还没有快照，手动补一次把名次固化；
     * ② 历史数据补录后，管理员认可「这个月该按新数据重排」，用 {@code force=true} 重算。</p>
     *
     * <p>{@code force=true} 会<b>改写已经公示过的历史名次</b>，故与查看分属两个权限点。</p>
     */
    @Operation(summary = "生成/补跑某周期快照（force=true 会覆盖已冻结的历史名次）")
    @SaCheckPermission(value = PermissionCode.HONOR_RANKING_SNAPSHOT, type = "admin")
    @PostMapping("/rankings/snapshots")
    public Result<SnapshotResultVO> generateSnapshot(
            @Parameter(description = "周期 1月/2年（总榜不支持）") @RequestParam Integer periodType,
            @Parameter(description = "周期标识：月 2026-07 / 年 2026") @RequestParam String periodKey,
            @Parameter(description = "是否覆盖已有快照，默认 false")
            @RequestParam(required = false, defaultValue = "false") boolean force) {
        return Result.ok(rankingService.generateSnapshot(periodType, periodKey, force));
    }
}
