package com.hengde.donate.service;

import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.constant.WishFlow;
import com.hengde.donate.dao.DonateStatsMapper;
import com.hengde.donate.vo.DonateStatsVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 后台「数据汇总」里属捐赠的部分（Row 79：微心愿 / 结对 / 捐书活动，V3 收尾批）。
 *
 * <p><b>口径收在 donate</b>（{@code DonateStatsMapper} 的注释），data 模块只调这里、不碰捐赠的表——
 * 与活动看板由 activity 的 {@code ActivityStatsService} 定口径是同一条规矩。</p>
 *
 * <p>「修建书屋数」系统里没有这个概念（没有书屋表、捐书活动也不记建了什么），恒为 null——
 * 给 0 会被读成「一个都没建」。见协会待确认清单㉒。</p>
 *
 * @author hengde
 */
@Service
public class DonateStatsService {

    private DonateStatsMapper statsMapper;

    @Autowired
    public void setStatsMapper(DonateStatsMapper statsMapper) {
        this.statsMapper = statsMapper;
    }

    public DonateStatsVOs.Summary summary() {
        DonateStatsVOs.Summary s = new DonateStatsVOs.Summary();

        Map<String, Object> w = statsMapper.wish(WishFlow.WISH_CLAIMED, WishFlow.WISH_REALIZED, WishFlow.CLAIM_REVOKED,
                DonateFlow.BIZ_WISH, DonateFlow.SHIPMENT_ARRIVED, DonateFlow.SHIPMENT_CHECKED);
        DonateStatsVOs.Wish wish = new DonateStatsVOs.Wish();
        wish.setPublished(num(w.get("published")));
        wish.setClaimed(num(w.get("claimed")));
        wish.setRealized(num(w.get("realized")));
        wish.setParticipants(num(w.get("participants")));
        wish.setParticipations(num(w.get("participations")));
        wish.setParcels(num(w.get("parcels")));
        s.setWish(wish);

        Map<String, Object> p = statsMapper.pair(PairFlow.PROJECT_DRAFT, PairFlow.PAIR_ESTABLISHED);
        DonateStatsVOs.Pair pair = new DonateStatsVOs.Pair();
        pair.setPublished(num(p.get("published")));
        pair.setEstablished(num(p.get("established")));
        pair.setParticipations(num(p.get("participations")));
        pair.setPledgedAmount(money(p.get("pledgedAmount")));
        pair.setRaisedAmount(money(p.get("raisedAmount")));
        s.setPair(pair);

        Map<String, Object> b = statsMapper.book(DonateFlow.BIZ_BOOK_CAMPAIGN, DonateFlow.SHIPMENT_CANCELLED,
                DonateFlow.SHIPMENT_ARRIVED, DonateFlow.SHIPMENT_CHECKED, DonateFlow.ITEM_QUALIFIED,
                DonateFlow.ITEM_PACKED, DonateFlow.ITEM_DELIVERED, DonateFlow.TYPE_BOOK, DonateFlow.TYPE_STATIONERY,
                DonateFlow.TYPE_SPORTS);
        DonateStatsVOs.Book book = new DonateStatsVOs.Book();
        book.setParticipants(num(b.get("participants")));
        book.setParcels(num(b.get("parcels")));
        book.setBooks(num(b.get("books")));
        book.setStationery(num(b.get("stationery")));
        book.setSports(num(b.get("sports")));
        book.setBookHouses(null);
        s.setBook(book);
        return s;
    }

    private static long num(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static BigDecimal money(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString()).setScale(2);
    }
}
