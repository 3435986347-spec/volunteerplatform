package com.hengde.organization.biz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 报名管理团队申请入参（固定表单；动态问卷构建器为后续预留，本期不做）。
 *
 * @author hengde
 */
@Getter
@Setter
public class ManagerApplyDTO {
    @NotBlank(message = "申请理由不能为空")
    @Size(max = 500, message = "申请理由不超过500字")
    private String reason;

    @Size(max = 500, message = "相关经历不超过500字")
    private String experience;

    @Size(max = 50, message = "期望部门不超过50字")
    private String expectDepartment;
}
