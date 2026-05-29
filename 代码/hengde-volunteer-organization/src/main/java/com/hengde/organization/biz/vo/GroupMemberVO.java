package com.hengde.organization.biz.vo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GroupMemberVO {
    private Long id;
    private Long volunteerId;
    private String realName;
    private String school;
    private String phone;
    private Integer role;
    private Integer status;
}
