package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 积分商品评价（Row 8）。
 *
 * <p><b>{@link #orderId} 是评价资格的凭据</b>，不是可选的关联：Row 8 只说「商品的评价」，
 * 没说谁能评。比照 {@code AttendanceService.submitReview} 那条「须实际签到才能评」的形状，
 * 评价必须挂在一张属于本人的、已领取的兑换单上；{@code uk_order} 保证一单一评。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_goods_review")
public class MallGoodsReview extends BaseEntity {

    /** 商品 id */
    private Long goodsId;
    /** 兑换单 id——评价资格的凭据 */
    private Long orderId;
    /** 评价人 */
    private Long volunteerId;
    /** 评分 1~5 */
    private Integer rating;
    /** 评价内容 */
    private String content;
    /** 1正常/0已下架 */
    private Integer status;
}
