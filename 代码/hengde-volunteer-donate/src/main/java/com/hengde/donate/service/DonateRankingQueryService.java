package com.hengde.donate.service;

import com.hengde.activity.vo.RankingRowView;
import com.hengde.donate.constant.WishFlow;
import com.hengde.donate.dao.DonateWishClaimMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 排行榜的 donate 数据源（只读），供 honor 的微心愿板块（Row 18，{@code rank_type = 4}）。
 *
 * <p>与 activity 的 {@code ActivityRankingQueryService} 同一个形状、同一条纪律：
 * <b>「什么算圆了一个心愿」收在 donate 这一处</b>，honor 只排序、定名次、存快照，不自己拼 where——
 * 否则口径会和微心愿中心漂开（V3规划·微心愿批）。出参刻意不带姓名，由 honor 批量换名。</p>
 *
 * @author hengde
 */
@Service
public class DonateRankingQueryService {

    private DonateWishClaimMapper claimMapper;

    @Autowired
    public void setClaimMapper(DonateWishClaimMapper claimMapper) {
        this.claimMapper = claimMapper;
    }

    /**
     * 区间内实现的心愿数（按「已实现」算、按实现时间切周期，《协会待确认清单-v3》⑯ 默认）。
     *
     * @param from  含，null=不限
     * @param to    不含，null=不限
     * @param limit 取前几名
     */
    public List<RankingRowView> topByRealizedWishes(LocalDateTime from, LocalDateTime to, int limit) {
        return claimMapper.topRealized(from, to, limit, WishFlow.CLAIM_REALIZED);
    }
}
