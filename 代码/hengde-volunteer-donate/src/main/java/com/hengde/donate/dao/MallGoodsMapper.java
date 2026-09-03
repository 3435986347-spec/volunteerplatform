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
}
