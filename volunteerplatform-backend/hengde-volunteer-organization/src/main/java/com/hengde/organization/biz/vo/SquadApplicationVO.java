package com.hengde.organization.biz.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class SquadApplicationVO {
    private Long id;
    private Long squadId;
    /** 分队名（全局待审列表用，便于跨分队识别） */
    private String squadName;
    private Long volunteerId;
    private String volunteerName;
    private String reason;
    private Integer status;
    private String rejectReason;
    private LocalDateTime applyTime;
    private LocalDateTime auditTime;
    /** 1 后台账号 / 2 分队负责人（V87 之前审核的为空） */
    private Integer auditByType;
}
