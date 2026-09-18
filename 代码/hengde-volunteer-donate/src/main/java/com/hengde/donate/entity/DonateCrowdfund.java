package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 项目众筹（V52，Row 16）。本批只做项目管理与进度展示，捐款接 trade 在捐款批。
 *
 * <p>{@link #raisedAmount} 是<b>到账额</b>，本批恒为 0（没有支付），由捐款批写入。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_crowdfund")
public class DonateCrowdfund extends BaseEntity {

    private String title;
    private String coverUrl;
    private String detail;
    private BigDecimal targetAmount;
    private BigDecimal raisedAmount;
    /** 是否接受捐款（V62） */
    private Integer acceptMoney;
    /** 是否接受捐物（V62） */
    private Integer acceptGoods;
    private String goodsNeeded;
    private String recvName;
    private String recvPhone;
    private String recvAddress;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 见 {@code PairFlow.CROWDFUND_*} */
    private Integer status;
    private Long createBy;
}
