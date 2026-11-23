package com.hengde.donate.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 已领取的赞助商品兑换单（跨模块只读，供 enterprise 模块补记企业积分账本）。
 *
 * <p>是普通类不是 record：MyBatis 按列名映射到 setter，record 走构造器按列序映射，改一次 SELECT 列序就静默错位。</p>
 *
 * @author hengde
 */
@Data
public class SponsorPickedOrderView {
    private Long orderId;
    private Long enterpriseId;
    private String orderNo;
    private String goodsName;
    /** 商品部分实际扣的积分＝实际扣分 − 抵扣快递费的积分 */
    private Integer goodsPoints;
    private LocalDateTime pickupTime;
}
