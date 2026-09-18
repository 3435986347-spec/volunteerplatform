package com.hengde.donate.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * 后台「数据汇总」里属捐赠的三块（Row 79：微心愿数据 / 结对数据 / 捐书活动数据，V3 收尾批）。
 *
 * <p><b>口径只在这里写一次</b>（data 模块只调 {@code DonateStatsService}、不碰这些表）。每块一条语句、一次往返。
 * 状态码一律由调用方传入，不在 SQL 里写死魔法数。</p>
 *
 * @author hengde
 */
@Mapper
public interface DonateStatsMapper {

    /**
     * 微心愿数据。
     * <ul>
     *   <li><b>发布总数</b>＝心愿总数（含已下架——发布过就算）；</li>
     *   <li><b>认领成功</b>＝此刻处于「已认领 / 已实现」的心愿数（认领被取消、被撤销后心愿回到待认领，不算）；</li>
     *   <li><b>参与人次 / 人数</b>＝认领记录数及其去重人数，<b>不含被协会撤销的</b>（那是资料有误被收回，不是参与）；
     *       本人取消的算——他确实参与过；</li>
     *   <li><b>收到包裹</b>＝微心愿运单已确认到货（已到货 / 已核对）。</li>
     * </ul>
     */
    @Select("SELECT "
            + "(SELECT COUNT(*) FROM donate_wish WHERE is_deleted = 0) AS published, "
            + "(SELECT COUNT(*) FROM donate_wish WHERE is_deleted = 0 AND status IN (#{wishClaimed}, #{wishRealized})) AS claimed, "
            + "(SELECT COUNT(*) FROM donate_wish WHERE is_deleted = 0 AND status = #{wishRealized}) AS realized, "
            + "(SELECT COUNT(DISTINCT volunteer_id) FROM donate_wish_claim WHERE is_deleted = 0 AND status <> #{claimRevoked}) AS participants, "
            + "(SELECT COUNT(*) FROM donate_wish_claim WHERE is_deleted = 0 AND status <> #{claimRevoked}) AS participations, "
            + "(SELECT COUNT(*) FROM donate_shipment WHERE is_deleted = 0 AND biz_type = #{bizWish} "
            + "   AND status IN (#{arrived}, #{checked})) AS parcels")
    Map<String, Object> wish(@Param("wishClaimed") int wishClaimed, @Param("wishRealized") int wishRealized,
                             @Param("claimRevoked") int claimRevoked, @Param("bizWish") int bizWish,
                             @Param("arrived") int arrived, @Param("checked") int checked);

    /**
     * 结对数据。
     * <ul>
     *   <li><b>发布总数</b>＝上过架的结对项目（不含草稿）；</li>
     *   <li><b>结对成功</b>＝此刻「结对成立」的登记数；</li>
     *   <li><b>参与人次</b>＝全部登记次数（含待确认与已取消——每登记一次算一次）；</li>
     *   <li><b>结对金额</b>给两个数：<b>认捐额</b>（协会确认成立时累加）与<b>到账额</b>（捐款批起真正收到的钱）——
     *       只给前者会把没到账的钱当成筹到的，只给后者在微信支付开通前恒为 0。</li>
     * </ul>
     */
    @Select("SELECT "
            + "(SELECT COUNT(*) FROM donate_pair_project WHERE is_deleted = 0 AND status <> #{projectDraft}) AS published, "
            + "(SELECT COUNT(*) FROM donate_pair_record WHERE is_deleted = 0 AND status = #{established}) AS established, "
            + "(SELECT COUNT(*) FROM donate_pair_record WHERE is_deleted = 0) AS participations, "
            + "(SELECT COALESCE(SUM(pledged_amount), 0) FROM donate_pair_project WHERE is_deleted = 0) AS pledgedAmount, "
            + "(SELECT COALESCE(SUM(raised_amount), 0) FROM donate_pair_project WHERE is_deleted = 0) AS raisedAmount")
    Map<String, Object> pair(@Param("projectDraft") int projectDraft, @Param("established") int established);

    /**
     * 捐书活动数据——<b>与单个活动的「本次活动数据」同一口径</b>（{@code DonateCampaignMapper.selectStats}），
     * 只是跨全部活动汇总：各活动的统计截止照样生效，所以平台总数＝各活动数之和（参加人数除外——
     * 同一个人参加了两次活动只算一个人）。
     */
    @Select("SELECT "
            + "(SELECT COUNT(DISTINCT s.donor_volunteer_id) FROM donate_shipment s JOIN donate_campaign c ON c.id = s.biz_id "
            + "   WHERE s.biz_type = #{bizBook} AND s.status <> #{cancelled} AND s.is_deleted = 0 AND c.is_deleted = 0 "
            + "   AND (c.stats_deadline IS NULL OR s.ship_time <= c.stats_deadline)) AS participants, "
            + "(SELECT COUNT(*) FROM donate_shipment s JOIN donate_campaign c ON c.id = s.biz_id "
            + "   WHERE s.biz_type = #{bizBook} AND s.status IN (#{arrived}, #{checked}) AND s.is_deleted = 0 AND c.is_deleted = 0 "
            + "   AND (c.stats_deadline IS NULL OR s.arrive_time <= c.stats_deadline)) AS parcels, "
            + "(SELECT COALESCE(SUM(CASE WHEN i.item_type = #{typeBook} THEN i.quantity ELSE 0 END), 0) FROM donate_item i "
            + "   JOIN donate_shipment s ON s.id = i.shipment_id JOIN donate_campaign c ON c.id = i.biz_id "
            + "   WHERE i.biz_type = #{bizBook} AND i.status IN (#{qualified}, #{packed}, #{delivered}) AND i.is_deleted = 0 "
            + "   AND c.is_deleted = 0 AND (c.stats_deadline IS NULL OR s.arrive_time <= c.stats_deadline)) AS books, "
            + "(SELECT COALESCE(SUM(CASE WHEN i.item_type = #{typeStationery} THEN i.quantity ELSE 0 END), 0) FROM donate_item i "
            + "   JOIN donate_shipment s ON s.id = i.shipment_id JOIN donate_campaign c ON c.id = i.biz_id "
            + "   WHERE i.biz_type = #{bizBook} AND i.status IN (#{qualified}, #{packed}, #{delivered}) AND i.is_deleted = 0 "
            + "   AND c.is_deleted = 0 AND (c.stats_deadline IS NULL OR s.arrive_time <= c.stats_deadline)) AS stationery, "
            + "(SELECT COALESCE(SUM(CASE WHEN i.item_type = #{typeSports} THEN i.quantity ELSE 0 END), 0) FROM donate_item i "
            + "   JOIN donate_shipment s ON s.id = i.shipment_id JOIN donate_campaign c ON c.id = i.biz_id "
            + "   WHERE i.biz_type = #{bizBook} AND i.status IN (#{qualified}, #{packed}, #{delivered}) AND i.is_deleted = 0 "
            + "   AND c.is_deleted = 0 AND (c.stats_deadline IS NULL OR s.arrive_time <= c.stats_deadline)) AS sports")
    Map<String, Object> book(@Param("bizBook") int bizBook, @Param("cancelled") int cancelled,
                             @Param("arrived") int arrived, @Param("checked") int checked,
                             @Param("qualified") int qualified, @Param("packed") int packed,
                             @Param("delivered") int delivered, @Param("typeBook") int typeBook,
                             @Param("typeStationery") int typeStationery, @Param("typeSports") int typeSports);
}
