package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 商品规格与库存。
 *
 * <p><b>{@link #stock} 是全系统唯一的库存存放处。</b> 扣减一律走
 * {@code MallGoodsSpecMapper.deductStock} 的单语句 CAS，<b>不要先查再改</b>——
 * 先查再改既有超卖问题，又会因为「读完就改这一行」而在并发下形成锁升级死锁。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_goods_spec")
public class MallGoodsSpec extends BaseEntity {

    /** 所属商品 */
    private Long goodsId;
    /** 规格名（Row 8 C「我的兑换」要展示） */
    private String name;
    /** 所需积分 */
    private Integer points;
    /** 现金部分【分】，0=纯积分（V57，清单②默认：固定积分 + 固定现金） */
    private Integer cashFen;
    /** 库存 */
    private Integer stock;
    /** 排序 */
    private Integer sort;
}
