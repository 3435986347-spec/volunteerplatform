package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.donate.entity.DonateCampaign;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * DonateCampaign Mapper。
 *
 * @author hengde
 */
public interface DonateCampaignMapper extends BaseMapper<DonateCampaign> {

    /**
     * 「本次活动数据」（Row 17 C：参加人数、收到包裹、收到课外书籍、收到学习用品、收到运动器材），
     * <b>一批活动一次查完</b>——列表页逐个活动算会 N+1。
     *
     * <p>口径（写在一处，志愿者端与后台共用）：</p>
     * <ul>
     *   <li><b>参加人数</b>＝有未取消运单的去重捐赠人；</li>
     *   <li><b>收到包裹</b>＝已确认到货（已到货 / 已核对）的运单数；</li>
     *   <li><b>收到 X</b>＝核对<b>合格</b>的该类物资数量之和（合格 / 已装箱 / 已送达）——
     *       不合格要退回的不算「收到」，否则数字里会有一批最后被寄回去的东西；</li>
     *   <li><b>统计截止</b>：活动设了 {@code stats_deadline} 时，只算截止前到货的运单与其物资。</li>
     * </ul>
     *
     * <p>{@code bizType} 由调用方传入（捐书活动 = 1），不在 SQL 里写死魔法数。</p>
     */
    @Select("<script>SELECT c.id AS campaignId, "
            + "(SELECT COUNT(DISTINCT s.donor_volunteer_id) FROM donate_shipment s "
            + "  WHERE s.biz_type = #{bizType} AND s.biz_id = c.id AND s.status &lt;&gt; #{cancelled} AND s.is_deleted = 0 "
            + "  AND (c.stats_deadline IS NULL OR s.ship_time &lt;= c.stats_deadline)) AS participants, "
            + "(SELECT COUNT(*) FROM donate_shipment s "
            + "  WHERE s.biz_type = #{bizType} AND s.biz_id = c.id AND s.status IN (#{arrived}, #{checked}) AND s.is_deleted = 0 "
            + "  AND (c.stats_deadline IS NULL OR s.arrive_time &lt;= c.stats_deadline)) AS parcels, "
            + "(SELECT COALESCE(SUM(CASE WHEN i.item_type = #{typeBook} THEN i.quantity ELSE 0 END), 0) "
            + "  FROM donate_item i JOIN donate_shipment s ON s.id = i.shipment_id "
            + "  WHERE i.biz_type = #{bizType} AND i.biz_id = c.id AND i.status IN (#{qualified}, #{packed}, #{delivered}) "
            + "  AND i.is_deleted = 0 AND (c.stats_deadline IS NULL OR s.arrive_time &lt;= c.stats_deadline)) AS books, "
            + "(SELECT COALESCE(SUM(CASE WHEN i.item_type = #{typeStationery} THEN i.quantity ELSE 0 END), 0) "
            + "  FROM donate_item i JOIN donate_shipment s ON s.id = i.shipment_id "
            + "  WHERE i.biz_type = #{bizType} AND i.biz_id = c.id AND i.status IN (#{qualified}, #{packed}, #{delivered}) "
            + "  AND i.is_deleted = 0 AND (c.stats_deadline IS NULL OR s.arrive_time &lt;= c.stats_deadline)) AS stationery, "
            + "(SELECT COALESCE(SUM(CASE WHEN i.item_type = #{typeSports} THEN i.quantity ELSE 0 END), 0) "
            + "  FROM donate_item i JOIN donate_shipment s ON s.id = i.shipment_id "
            + "  WHERE i.biz_type = #{bizType} AND i.biz_id = c.id AND i.status IN (#{qualified}, #{packed}, #{delivered}) "
            + "  AND i.is_deleted = 0 AND (c.stats_deadline IS NULL OR s.arrive_time &lt;= c.stats_deadline)) AS sports "
            + "FROM donate_campaign c WHERE c.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Map<String, Object>> selectStats(@Param("ids") Collection<Long> ids, @Param("bizType") int bizType,
                                          @Param("cancelled") int cancelled, @Param("arrived") int arrived,
                                          @Param("checked") int checked, @Param("qualified") int qualified,
                                          @Param("packed") int packed, @Param("delivered") int delivered,
                                          @Param("typeBook") int typeBook, @Param("typeStationery") int typeStationery,
                                          @Param("typeSports") int typeSports);

    /** 当前可报名（已发布且在活动时间内）的活动是否存在——下单前的闸门由服务层用它判定。 */
    @Select("SELECT COUNT(*) FROM donate_campaign WHERE id = #{id} AND status = #{published} AND is_deleted = 0 "
            + "AND start_time <= #{now} AND end_time > #{now}")
    int countOpen(@Param("id") Long id, @Param("published") int published, @Param("now") LocalDateTime now);
}
