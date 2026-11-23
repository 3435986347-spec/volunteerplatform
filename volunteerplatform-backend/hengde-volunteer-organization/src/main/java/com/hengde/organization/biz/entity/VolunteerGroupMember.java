package com.hengde.organization.biz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("volunteer_group_member")
public class VolunteerGroupMember extends BaseEntity {
    private Long groupId;
    private Long volunteerId;
    /** 0=普通成员 / 1=组长 / 2=管理员（V7 起接口启用，≤3 由 GroupService 校验） */
    private Integer role;
    private Integer status;
    private LocalDateTime applyTime;
    private LocalDateTime auditTime;
    /** 审批人 volunteer.id（V7，组长或管理员） */
    private Long auditBy;
}
