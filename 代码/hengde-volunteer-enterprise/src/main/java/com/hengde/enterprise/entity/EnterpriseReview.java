package com.hengde.enterprise.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 赞助商评价（V81，Row 74）。一单一评；屏蔽与删除是两件事。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("enterprise_review")
public class EnterpriseReview extends BaseEntity {

    /** 正常（对外可见） */
    public static final int NORMAL = 0;
    /** 已屏蔽（后台藏起来；作者与后台仍看得到，可恢复） */
    public static final int HIDDEN = 1;

    private Long enterpriseId;
    private Long volunteerId;
    private Long orderId;
    private Integer rating;
    private String content;
    private Integer status;
    private Long hiddenBy;
    private LocalDateTime hiddenTime;
    private String hiddenReason;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long activeOrderKey;
}
