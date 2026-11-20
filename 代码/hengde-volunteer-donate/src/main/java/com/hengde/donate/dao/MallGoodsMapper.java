package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.donate.entity.MallGoods;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MallGoods Mapper。
 *
 * @author hengde
 */
public interface MallGoodsMapper extends BaseMapper<MallGoods> {

    /**
     * 按 id 取商品并加<b>共享行锁</b>（{@code FOR SHARE}），必须在事务内调用。
     *
     * <p>供审核路径用：{@code approve}/{@code reject} 要确认「这一刻它仍是待审核」。
     * 普通 {@code selectById} 在 RR 下是快照读——事务里第一条 SELECT 就把读视图定死，
     * 此后别人把它改回草稿并提交，这里仍读到旧的「待审核」。</p>
     *
     * <p><b>为什么是 S 不是 X</b>：这里读完<b>不改商品这一行</b>的判定依据，
     * 真正的状态迁移交给带 CAS 条件的 UPDATE（影响行数判定）。判据与
     * {@code HonorMedalMapper.selectByIdForShare} 一致：读完不改就用共享锁。
     * <b>下单路径不要用这个方法</b>——那里读完就要改库存，见
     * {@code MallGoodsSpecMapper.deductStock} 的说明。</p>
     *
     * @param id 商品 id
     * @return 商品行；不存在或已逻辑删除返回 null
     */
    @Select("SELECT * FROM mall_goods WHERE id = #{id} AND is_deleted = 0 FOR SHARE")
    MallGoods selectByIdForShare(@Param("id") Long id);

    /**
     * 志愿者端货架。排序分支固定在 SQL 中，客户端只传白名单里的名称；
     * 积分排序按未删除规格的最低积分，缺规格的商品始终排在有积分商品之后。
     */
    @Select("<script>"
            + "SELECT g.* FROM mall_goods g "
            + "LEFT JOIN (SELECT goods_id, MIN(points) AS min_points FROM mall_goods_spec "
            + "WHERE is_deleted = 0 GROUP BY goods_id) sp ON sp.goods_id = g.id "
            + "WHERE g.is_deleted = 0 AND g.status = #{onSale} AND g.hidden = 0 AND g.sponsor_suspended = 0 "
            + "<if test='sponsorEnterpriseId != null'>AND g.sponsor_enterprise_id = #{sponsorEnterpriseId} </if>"
            + "<if test='keyword != null and keyword != \"\"'>AND g.name LIKE CONCAT('%', #{keyword}, '%') </if>"
            + "<if test='category == \"physical\"'>AND (g.virtual_goods IS NULL OR g.virtual_goods = 0) </if>"
            + "<if test='category == \"virtual\"'>AND g.virtual_goods = 1 </if>"
            + "<choose>"
            + "<when test='sort == \"pointsAsc\"'>ORDER BY sp.min_points IS NULL, sp.min_points ASC, g.id DESC</when>"
            + "<when test='sort == \"pointsDesc\"'>ORDER BY sp.min_points IS NULL, sp.min_points DESC, g.id DESC</when>"
            + "<otherwise>ORDER BY g.sort ASC, g.id DESC</otherwise>"
            + "</choose>"
            + "</script>")
    com.baomidou.mybatisplus.core.metadata.IPage<MallGoods> selectVolunteerPage(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<MallGoods> page,
            @Param("keyword") String keyword,
            @Param("sponsorEnterpriseId") Long sponsorEnterpriseId,
            @Param("category") String category,
            @Param("sort") String sort,
            @Param("onSale") int onSale);
}
