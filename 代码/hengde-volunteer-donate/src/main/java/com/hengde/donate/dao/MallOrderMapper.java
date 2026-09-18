package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.donate.entity.MallOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MallOrder Mapper。
 *
 * <p>状态迁移一律走带 CAS 条件的 {@code LambdaUpdateWrapper}（在 service 里），
 * 靠影响行数判定，不在这里另开手写语句。</p>
 *
 * @author hengde
 */
public interface MallOrderMapper extends BaseMapper<MallOrder> {

    /**
     * 按取货码<b>当前读</b>（共享锁）取单。用于核销 CAS 之后的复核与说明。
     *
     * <p><b>为什么不能用普通读</b>：核销员路径在 CAS 之前已经做过普通读（查核销员资格、预查目标单），
     * RR 的读视图定在那一刻。两个窗口同时扫同一个码时，输家的 CAS 等赢家提交后返回 0，
     * 再用普通读复核看到的仍是「待领取」，于是把「该取货码已于 X 核销过」报成「当前不可领取（待领取）」——
     * 柜台前的人完全看不懂。{@code MallVerifierTest.theLosingVerifierIsToldItWasAlreadyPickedUp} 钉住它。
     * 只读比对，所以取 S。</p>
     */
    @Select("SELECT * FROM mall_order WHERE pickup_code = #{code} AND is_deleted = 0 FOR SHARE")
    MallOrder selectByPickupCodeForShare(@Param("code") String code);

    /**
     * 赞助商品的已领取兑换单（V4 爱心企业批，企业积分账本补记用）：领取时间不早于 {@code since}、id 大于 {@code afterId}，按 id 翻页。
     *
     * <p>商品可能在领取之后被删，所以联商品表<b>不带</b> {@code g.is_deleted = 0}——东西已经交出去了，企业的积分照记。</p>
     */
    @Select("SELECT o.id AS orderId, g.sponsor_enterprise_id AS enterpriseId, o.order_no AS orderNo, o.goods_name AS goodsName, "
            + "o.points - COALESCE(o.shipping_points, 0) AS goodsPoints, o.pickup_time AS pickupTime "
            + "FROM mall_order o JOIN mall_goods g ON g.id = o.goods_id "
            + "WHERE o.status = #{picked} AND o.is_deleted = 0 AND o.pickup_time >= #{since} AND o.id > #{afterId} "
            + "AND g.sponsor_enterprise_id IS NOT NULL ORDER BY o.id LIMIT #{limit}")
    java.util.List<com.hengde.donate.vo.SponsorPickedOrderView> selectPickedSponsorOrders(@Param("picked") int picked,
            @Param("since") java.time.LocalDateTime since, @Param("afterId") long afterId, @Param("limit") int limit);

    /** 这张单是不是「他自己的、已领取的、赞助商品」的兑换单（赞助商评价的资格判定，V4 爱心企业批·社区段）。 */
    @Select("SELECT o.id AS orderId, g.sponsor_enterprise_id AS enterpriseId, o.order_no AS orderNo, o.goods_name AS goodsName, "
            + "o.points - COALESCE(o.shipping_points, 0) AS goodsPoints, o.pickup_time AS pickupTime "
            + "FROM mall_order o JOIN mall_goods g ON g.id = o.goods_id "
            + "WHERE o.id = #{orderId} AND o.volunteer_id = #{volunteerId} AND o.status = #{picked} AND o.is_deleted = 0 "
            + "AND g.sponsor_enterprise_id IS NOT NULL")
    com.hengde.donate.vo.SponsorPickedOrderView selectPickedSponsorOrder(@Param("orderId") Long orderId,
            @Param("volunteerId") Long volunteerId, @Param("picked") int picked);
}
