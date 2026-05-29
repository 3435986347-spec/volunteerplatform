package com.hengde.organization.biz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("volunteer_group")
public class VolunteerGroup extends BaseEntity {
    private String groupNo;
    private String name;
    private String description;
    private Long leaderId;
    private Integer status;
    /** 拒绝建组原因。V7 起仅承载「拒绝建组」语义；解散原因走 dissolveReason 字段 */
    private String rejectReason;

    /** 解散时间（V7） */
    private LocalDateTime dissolveTime;
    /** 解散原因（V7，与 rejectReason 解耦） */
    private String dissolveReason;
    /** 解散操作管理员 admin_user.id（V7） */
    private Long dissolveBy;

    /** 建组审批通过时间（V7，≠ createTime——createTime 为申请时间） */
    private LocalDateTime approvedTime;
    /** 审批管理员 admin_user.id（V7） */
    private Long approvedBy;
}
