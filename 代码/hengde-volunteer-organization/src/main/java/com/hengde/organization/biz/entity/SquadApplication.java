package com.hengde.organization.biz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("squad_application")
public class SquadApplication extends BaseEntity {
    private Long squadId;
    private Long volunteerId;
    private String reason;
    private Integer status;
    private String rejectReason;
    private LocalDateTime applyTime;
    private LocalDateTime auditTime;
    /** 审核人 id：{@link #auditByType} 为 1 时是 admin_user.id，为 2 时是 volunteer.id（分队负责人）。V87 之前的为 NULL */
    private Long auditBy;
    /** 1 后台账号 / 2 分队负责人——两套 id 不同源，不带类型就分不清是谁审的 */
    private Integer auditByType;
}
