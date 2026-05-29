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
}
