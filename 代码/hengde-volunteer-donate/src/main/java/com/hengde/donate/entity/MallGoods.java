package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 积分商品（Row 8）。
 *
 * <p><b>这里没有 stock 字段，是刻意的</b>——库存只在 {@link MallGoodsSpec} 上。
 * 两处都存库存 = 两个口径，是本项目反复栽的那类跟头。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_goods")
public class MallGoods extends BaseEntity {

    /** 商品名称 */
    private String name;
    /** 商品图片 */
    private String coverUrl;
    /** 商品详情（图文） */
    private String detail;
    /**
     * 赞助企业 id。
     *
     * <p>Row 8 说「商品赞助单位需要入驻爱心企业」，但 enterprise 模块不在 V3 ⇒
     * <b>V3 恒为 null</b>，赞助方以 {@link #sponsorName} 快照展示，由后台代发布
     * （Row 15 F 原文：「后台可以以企业的名义代替企业发布积分商品」）。</p>
     */
    private Long sponsorEnterpriseId;
    /** 赞助方名称快照 */
    private String sponsorName;
    /** 赞助企业当前不可用（暂停 / 删除，V78）：1=志愿者不可见、不可兑换；由 enterprise 模块整批置位 */
    private Integer sponsorSuspended;
    /**
     * 必须使用的卷定义 id（V47，Row 8 F「商品只能使用指定卷才能兑换，没有卷就不能兑换」）。
     * null = 不要求。它是「谁能买、花多少」的一部分，改它与改价格一样要退回重审。
     */
    private Long requireCouponId;
    /** 审核 / 上架状态，见 {@code MallGoodsStatus} */
    private Integer status;
    /** 隐藏 0否/1是；与 status 正交，改它不触发重审 */
    private Integer hidden;
    /** 排序，纯展示；改它不触发重审 */
    private Integer sort;
    /** 提交审核时间 */
    private LocalDateTime submitTime;
    /** 审核人 admin_user.id */
    private Long reviewBy;
    /** 审核时间 */
    private LocalDateTime reviewTime;
    /** 驳回原因 */
    private String rejectReason;
}
