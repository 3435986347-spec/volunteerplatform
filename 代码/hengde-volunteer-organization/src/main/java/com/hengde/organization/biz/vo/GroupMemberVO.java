package com.hengde.organization.biz.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

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
    /** 申请时间（待审核加入申请列表用，便于组长按先后处理；在册成员名单也回显） */
    private LocalDateTime applyTime;
}
