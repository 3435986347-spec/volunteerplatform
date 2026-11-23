package com.hengde.enterprise.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 爱心企业的数据汇总口径（V4 数据汇总批，Row 79「爱心企业数据（爱心企业数、发布商品数、发放商品数、已兑换商品数、收回积分数、评价条数）」）。
 *
 * <p><b>「已兑换」与「已发放」是两件事</b>：下单即扣分占库存（已兑换），东西到手才叫发放（已领取）——
 * 合成一个数会让协会以为东西都送出去了，而柜台上可能还压着一堆没人来取的。</p>
 *
 * <p><b>「收回积分数」＝企业积分账本的入账合计</b>（兑换入账那一类），不含后台手工调整：
 * 那是协会与企业结算时的账，混进去就说不清企业到底从兑换里收回了多少分。</p>
 *
 * @author hengde
 */
@Service
public class EnterpriseStatsService {

    private JdbcTemplate jdbc;

    @Autowired
    public void setJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 爱心企业数（没删的；含待审核 / 暂停，状态分布另有后台列表）。 */
    public long countEnterprises() {
        return one("SELECT COUNT(*) FROM enterprise_account WHERE is_deleted = 0");
    }

    /** 正常（审核通过且没暂停）的企业数。 */
    public long countNormalEnterprises() {
        return one("SELECT COUNT(*) FROM enterprise_account WHERE is_deleted = 0 AND status = 1");
    }

    /** 企业发布的商品数（赞助商品，没删的）。 */
    public long countSponsorGoods() {
        return one("SELECT COUNT(*) FROM mall_goods WHERE is_deleted = 0 AND sponsor_enterprise_id IS NOT NULL");
    }

    /** 已兑换（下单即占住积分与库存）的赞助商品单数——含还没去取的。 */
    public long countSponsorOrders() {
        return one("SELECT COUNT(*) FROM mall_order o JOIN mall_goods g ON g.id = o.goods_id "
                + "WHERE o.is_deleted = 0 AND g.sponsor_enterprise_id IS NOT NULL");
    }

    /** 已发放（东西到手：现场核销 / 本人确认收货 / 系统自动确认）的赞助商品单数。 */
    public long countSponsorOrdersPicked() {
        return one("SELECT COUNT(*) FROM mall_order o JOIN mall_goods g ON g.id = o.goods_id "
                + "WHERE o.is_deleted = 0 AND g.sponsor_enterprise_id IS NOT NULL AND o.status = 3");
    }

    /** 企业收回的积分数（兑换入账合计）。 */
    public long sumCreditedPoints() {
        // 这张表只追加、没有逻辑删除列（V79）
        return one("SELECT COALESCE(SUM(change_amount), 0) FROM enterprise_point_record WHERE source_type = 1");
    }

    /** 赞助商评价条数（没删的，含被屏蔽的——屏蔽只是对外藏起来，评价本身还在）。 */
    public long countReviews() {
        return one("SELECT COUNT(*) FROM enterprise_review WHERE is_deleted = 0");
    }

    private long one(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }
}
