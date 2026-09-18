package com.hengde.data.complaint.dto;

import com.hengde.organization.form.dto.FormDTOs;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 投诉建议入参（V4 投诉建议批）。
 *
 * @author hengde
 */
public final class ComplaintDTOs {

    private ComplaintDTOs() {
    }

    @Data
    @Schema(description = "提交投诉建议")
    public static class Submit {

        @NotNull(message = "请选择类型")
        @Schema(description = "1投诉/2建议")
        private Integer type;

        @NotBlank(message = "请填写内容")
        @Size(max = 2000, message = "内容不超过 2000 字")
        private String content;

        @Schema(description = "图片 URL（最多 6 张，先经 POST /v/files/form-file 上传）")
        @Size(max = 6, message = "图片最多 6 张")
        private List<String> images;

        @Schema(description = "协会为「投诉建议」发布了问卷时的答案（先调 GET /v/organization/forms/scenes/5/current）；没有问卷时不传")
        @Valid
        @Size(max = 100, message = "答案过多")
        private List<FormDTOs.Answer> answers;
    }

    @Data
    @Schema(description = "流转到其他部门")
    public static class Transfer {

        @NotBlank(message = "请选择要转交的部门")
        @Size(max = 32, message = "部门名称过长")
        private String department;

        @Schema(description = "转交理由（仅后台可见）")
        @Size(max = 500, message = "转交理由不超过 500 字")
        private String reason;
    }

    @Data
    @Schema(description = "答复并办结 / 内部备注")
    public static class Text {

        @NotBlank(message = "请填写内容")
        @Size(max = 2000, message = "内容不超过 2000 字")
        private String content;
    }
}
