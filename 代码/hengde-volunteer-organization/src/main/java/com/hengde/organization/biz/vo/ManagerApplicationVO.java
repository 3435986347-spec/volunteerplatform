package com.hengde.organization.biz.vo;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 报名管理团队申请出参（后台审核列表带 volunteerName；志愿者端「我的申请」状态回显）。
 *
 * @author hengde
 */
@Getter
@Setter
public class ManagerApplicationVO {
    private Long id;
    private Long volunteerId;
    /** 申请人姓名（后台列表用，志愿者端可忽略） */
    private String volunteerName;
    private String reason;
    private String experience;
    private String expectDepartment;
    /** 0待审核/1已通过/2已驳回 */
    private Integer status;
    private String rejectReason;
    private LocalDateTime applyTime;
    private LocalDateTime auditTime;
}
