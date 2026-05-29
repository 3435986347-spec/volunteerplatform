package com.hengde.api.controller;

import com.hengde.activity.service.ActivityService;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.common.search.SearchItemVO;
import com.hengde.organization.biz.service.GroupService;
import com.hengde.organization.biz.service.SquadService;
import com.hengde.publicity.service.PublicityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 全局搜索：跨领域（活动 + 公告 + 小组 + 分队）搜索，结果在 api 层合并成单一信息流。
 * 对应 CLAUDE.md：全局搜索落在 common/api 聚合层。
 *
 * <p>面向小程序：返回一个混合类型的列表（带标题/封面图），前端下滑用 {@code page+1} 加载下一页并追加，
 * 无需翻页按钮。各领域固定顺序拼成一个逻辑长列表（活动→公告→小组→分队），
 * 用各领域真实命中数算精确 {@code total}，再按全局 {@code offset/limit} 跨块取窗口——无截断、下滑不漏。</p>
 */
@Tag(name = "全局搜索")
@RestController
@RequestMapping("/v/search")
public class SearchController {

    private ActivityService activityService;
    private PublicityService publicityService;
    private GroupService groupService;
    private SquadService squadService;

    @Autowired
    public void setActivityService(ActivityService activityService) {
        this.activityService = activityService;
    }

    @Autowired
    public void setPublicityService(PublicityService publicityService) {
        this.publicityService = publicityService;
    }

    @Autowired
    public void setGroupService(GroupService groupService) {
        this.groupService = groupService;
    }

    @Autowired
    public void setSquadService(SquadService squadService) {
        this.squadService = squadService;
    }

    @Operation(summary = "全局搜索（活动+公告+小组+分队合并为单一信息流，下滑加载）")
    @GetMapping
    public Result<PageResult<SearchItemVO>> search(
            @Parameter(description = "搜索关键词") @RequestParam String keyword,
            PageQuery query) {
        var pageInfo = query.toPage();
        long page = pageInfo.getCurrent();
        long size = pageInfo.getSize();
        long from = (page - 1) * size;
        long to = from + size;

        // 固定顺序的领域块：活动 → 公告 → 小组 → 分队（每领域命中数只查一次）
        long activityCount = activityService.countSearch(keyword);
        long announcementCount = publicityService.countSearch(keyword);
        long groupCount = groupService.countSearch(keyword);
        long squadCount = squadService.countSearch(keyword);
        long total = activityCount + announcementCount + groupCount + squadCount;

        List<SearchItemVO> items = new ArrayList<>();
        long cursor = 0;
        cursor = collect(items, from, to, cursor, activityCount,
                (off, lim) -> activityService.search(keyword, off, lim));
        cursor = collect(items, from, to, cursor, announcementCount,
                (off, lim) -> publicityService.search(keyword, off, lim));
        cursor = collect(items, from, to, cursor, groupCount,
                (off, lim) -> groupService.search(keyword, off, lim));
        collect(items, from, to, cursor, squadCount,
                (off, lim) -> squadService.search(keyword, off, lim));

        return Result.ok(PageResult.of(items, total, page, size));
    }

    /**
     * 取当前领域块与全局窗口 [from, to) 的交集，按块内偏移拉取并追加到结果。
     *
     * @return 下一个块的全局起始下标（= 本块结束下标）
     */
    private long collect(List<SearchItemVO> out, long from, long to, long blockStart, long blockCount,
                         BiFunction<Integer, Integer, List<SearchItemVO>> fetch) {
        long blockEnd = blockStart + blockCount;
        long s = Math.max(from, blockStart);
        long e = Math.min(to, blockEnd);
        if (s < e) {
            out.addAll(fetch.apply((int) (s - blockStart), (int) (e - s)));
        }
        return blockEnd;
    }
}
