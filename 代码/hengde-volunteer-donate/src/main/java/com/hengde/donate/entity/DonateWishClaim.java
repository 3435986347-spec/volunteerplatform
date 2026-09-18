package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 微心愿认领（V51）。生成列 {@code active_wish_key} 不映射（数据库计算，保证一个心愿同一时刻至多一条有效认领）。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_wish_claim")
public class DonateWishClaim extends BaseEntity {

    private Long wishId;
    private Long volunteerId;
    /** 见 {@code WishFlow.CLAIM_*} */
    private Integer status;
    private LocalDateTime claimTime;
    private LocalDateTime realizeTime;
    private LocalDateTime cancelTime;
    private Long cancelBy;
    private String cancelReason;
}
