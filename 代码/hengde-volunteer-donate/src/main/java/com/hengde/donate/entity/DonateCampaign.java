package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 公益捐书活动（V50，Row 17「首页展示本次活动名称、活动时间、状态、本次活动数据、分享、详情」）。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_campaign")
public class DonateCampaign extends BaseEntity {

    private String title;
    private String coverUrl;
    private String detail;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 0草稿/1已发布/2已结束，见 {@code DonateFlow.CAMPAIGN_*} */
    private Integer status;
    /** 收件电话（后台预留） */
    private String recvPhone;
    /** 收件地址前缀（后台预留；完整地址 = 前缀 + 捐赠人姓名） */
    private String recvAddress;
    /** 本次活动数据的统计截止时间；null=不截止 */
    private LocalDateTime statsDeadline;
    private Long createBy;
}
