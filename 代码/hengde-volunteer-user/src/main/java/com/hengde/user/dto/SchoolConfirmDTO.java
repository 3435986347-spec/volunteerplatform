package com.hengde.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 每年确认一次学校和年级（{@code POST /v/user/profile/school-confirm}）。
 * 协会 2026-09-16：「每年由志愿者确认学校和年级即可，审核自动通过，原则上不影响报名」。
 *
 * @author hengde
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Getter
@Setter
public class SchoolConfirmDTO {

    /** 学校（没变就原样提交） */
    @NotBlank(message = "请填写学校")
    @Size(max = 64, message = "学校名称不超过 64 字")
    private String school;

    /** 年级 code（与注册 / 我的资料同一套取值） */
    @NotNull(message = "请选择年级")
    private Integer grade;
}
