package com.hengde.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 订阅通知偏好（V66）：只存和默认（全开）不一样的行。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("volunteer_notify_pref")
public class VolunteerNotifyPref extends BaseEntity {

    private Long volunteerId;
    /** {@link com.hengde.auth.constant.NotifyTopic} 的名字 */
    private String topic;
    private Integer smsEnabled;
}
