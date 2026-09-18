package com.hengde.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 收货地址（V66，Row 40）。收件电话是密文。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_address")
public class UserAddress extends BaseEntity {

    private Long volunteerId;
    private String recvName;
    private String recvPhone;
    private String region;
    private String detail;
    private Integer isTop;
    private LocalDateTime topTime;
}
