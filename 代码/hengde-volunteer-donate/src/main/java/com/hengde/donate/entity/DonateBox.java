package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 装箱（V50，Row 17 第 9–10 步）。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_box")
public class DonateBox extends BaseEntity {

    private String boxCode;
    private Integer bizType;
    private Long bizId;
    /** 0装箱中/1已送达 */
    private Integer status;
    private Long recipientOrgId;
    private String recipientOrgName;
    private LocalDateTime deliverTime;
    private Long deliverBy;
    private Long createBy;
}
