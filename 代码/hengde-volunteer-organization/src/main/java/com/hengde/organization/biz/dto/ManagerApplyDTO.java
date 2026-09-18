package com.hengde.organization.biz.dto;

import com.hengde.organization.form.dto.FormDTOs;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 报名管理团队申请入参：固定三项（V23，照旧必填）+ 协会在后台发布了「报名管理团队」问卷时的答卷（V4 问卷引擎批）。
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

    @Schema(description = "报名问卷的答案（先调 GET /v/organization/forms/scenes/2/current 取问卷；没有问卷时不传）")
    @Valid
    @Size(max = 100, message = "答案过多")
    private List<FormDTOs.Answer> answers;
}
