package com.hengde.donate.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 结对项目的活动动态（V98，协会 2026-10-16 答复五 丙-2）：协会发放助学金 / 物资时的凭证，一律公开。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("donate_pair_update")
public class DonatePairUpdate extends BaseEntity {

    private Long projectId;
    private String content;
    /** 换行分隔的图片 URL */
    private String imageUrls;
    private LocalDateTime happenTime;
    private Long createBy;
}
