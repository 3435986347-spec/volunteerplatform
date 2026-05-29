package com.hengde.organization.biz.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class SquadApplicationVO {
    private Long id;
    private Long squadId;
    private Long volunteerId;
    private String volunteerName;
    private String reason;
    private Integer status;
    private String rejectReason;
    private LocalDateTime applyTime;
}
