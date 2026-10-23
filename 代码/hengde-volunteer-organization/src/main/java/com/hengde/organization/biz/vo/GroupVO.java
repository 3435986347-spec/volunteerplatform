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
    private String logoUrl;
    private Long leaderId;
    private String leaderName;
    private Integer status;
    private String rejectReason;
    private Long memberCount;
    private LocalDateTime createTime;
    /** 当前志愿者在本组的成员状态（志愿者端列表填）：null 不在组里 / 0 申请待审 / 1 已加入（组长也是 1） */
    private Integer myStatus;
}
