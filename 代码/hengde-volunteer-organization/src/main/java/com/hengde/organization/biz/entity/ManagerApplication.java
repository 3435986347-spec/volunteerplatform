package com.hengde.organization.biz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 报名管理团队申请：志愿者提交→后台审核→通过即置 volunteer.manager_flag=1（V23）。
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("manager_application")
public class ManagerApplication extends BaseEntity {
    private Long volunteerId;
    private String reason;
    private String experience;
    private String expectDepartment;
    /** 0待审核/1已通过/2已驳回 */
    private Integer status;
    private String rejectReason;
    private LocalDateTime applyTime;
    private Long auditBy;
    private LocalDateTime auditTime;
}
