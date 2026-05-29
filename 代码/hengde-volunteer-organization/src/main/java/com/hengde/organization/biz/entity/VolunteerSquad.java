package com.hengde.organization.biz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("volunteer_squad")
public class VolunteerSquad extends BaseEntity {
    private String name;
    private String type;
    private Long leaderId;
    private String leaderName;
    private String leaderPhone;
    private Integer memberLimit;
    private String visibleFields;
    private Integer status;
}
