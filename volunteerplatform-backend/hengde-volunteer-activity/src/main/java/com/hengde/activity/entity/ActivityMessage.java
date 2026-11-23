package com.hengde.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 活动留言（V15，V1.1 第 3 批·PR1）。
 *
 * <p>志愿者在活动下发表留言，活动留言列表对所有已登录用户可见；管理端（{@code activity:manage}）
 * 可逻辑删除下架。{@code status} 预留将来审核（0隐藏/1正常），当前发表即正常可见。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("activity_message")
public class ActivityMessage extends BaseEntity {

    /** 活动 activity.id */
    private Long activityId;

    /** 发表人 volunteer.id */
    private Long volunteerId;

    /** 留言内容 */
    private String content;

    /** 0隐藏/1正常（预留审核） */
    private Integer status;
}
