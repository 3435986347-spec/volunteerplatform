package com.hengde.enterprise.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 爱心企业账号（V77，Row 15）。负责人手机号是密文。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("enterprise_account")
public class EnterpriseAccount extends BaseEntity {

    private String name;
    private String creditCode;
    private String logoUrl;
    private String intro;
    private String address;
    private String contactPhone;
    private String leaderName;
    private String leaderPhone;
    private String leaderPhoneHash;
    private String username;
    private String password;
    private Integer status;
    private Integer source;
    private LocalDateTime submitTime;
    private String rejectReason;
    private Long auditBy;
    private LocalDateTime auditTime;
    private String pauseReason;
    private Long pausedBy;
    private LocalDateTime pausedTime;
    private Long createdBy;
    private LocalDateTime lastLoginTime;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeUsername;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeCreditCode;
}
