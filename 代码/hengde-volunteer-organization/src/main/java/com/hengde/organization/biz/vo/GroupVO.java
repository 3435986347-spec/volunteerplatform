package com.hengde.organization.biz.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class GroupVO {
    private Long id;
    private String groupNo;
    private String name;
    private String description;
    private Long leaderId;
    private String leaderName;
    private Integer status;
    private String rejectReason;
    private Long memberCount;
    private LocalDateTime createTime;
}
