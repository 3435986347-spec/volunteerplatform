package com.hengde.organization.form.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 问卷入参（V4 问卷引擎批）。Bean Validation 只挡明显的格式问题，题型相关的规则在 {@code FormAnswerValidator}。
 *
 * @author hengde
 */
public final class FormDTOs {

    private FormDTOs() {
    }

    @Data
    @Schema(description = "保存问卷（新建 / 修改草稿）：题目整批提交，按提交顺序编号")
    public static class Save {

        @Schema(description = "场景：1通用问卷（默认）/2报名管理团队/3评优评先/4意见反馈/5投诉建议")
        private Integer scene;

        @NotBlank(message = "请填写问卷标题")
        @Size(max = 128, message = "问卷标题不超过 128 字")
        private String title;

        @Size(max = 2000, message = "问卷说明不超过 2000 字")
        private String description;

        @Schema(description = "开始收集时间（空＝发布即开始）")
        private LocalDateTime startTime;

        @Schema(description = "截止时间（空＝直到手动停止）")
        private LocalDateTime endTime;

        @Schema(description = "每人只能提交一次（不传＝是；报名管理团队场景必须为否，次数由申请流程控制）")
        private Boolean singleSubmit;

        @Schema(description = "须已实名才能填写（不传＝是）")
        private Boolean requireRegistered;

        @Valid
        @NotEmpty(message = "问卷至少要有一道题")
        @Size(max = 100, message = "一份问卷最多 100 道题")
        private List<QuestionSave> questions;
    }

    @Data
    @Schema(description = "一道题")
    public static class QuestionSave {

        @NotNull(message = "请选择题型")
        @Schema(description = "1单选/2多选/3判断/4填空/5简答/6文件/7日期")
        private Integer type;

        @NotBlank(message = "请填写题目")
        @Size(max = 512, message = "题目不超过 512 字")
        private String title;

        @Size(max = 512, message = "题目说明不超过 512 字")
        private String description;

        @Schema(description = "是否必答（不传＝是）")
        private Boolean required;

        @Schema(description = "选项文字（单选 / 多选必填，2–50 个，不能重复）；选项编号 A、B、C… 由服务端分配")
        private List<String> options;

        @Schema(description = "填空 / 简答：字数上限（填空默认 100、最多 500；简答默认 1000、最多 5000）")
        private Integer maxLength;

        @Schema(description = "多选：最少选几项（默认必答时 1）")
        private Integer minSelect;

        @Schema(description = "多选：最多选几项（默认不限）")
        private Integer maxSelect;

        @Schema(description = "文件：最多几个（默认 3、最多 9）")
        private Integer maxFiles;

        @Schema(description = "日期：最早可选")
        private LocalDate minDate;

        @Schema(description = "日期：最晚可选")
        private LocalDate maxDate;
    }

    @Data
    @Schema(description = "一道题的答案")
    public static class Answer {

        @NotNull(message = "答案缺少题目 id")
        private Long questionId;

        @Schema(description = "单选：选项编号（\"A\"）；多选：编号数组（[\"A\",\"C\"]）；判断：true/false；"
                + "填空 / 简答：文字；文件：上传得到的 URL 数组；日期：\"yyyy-MM-dd\"。可选题不答可以不传或传空")
        private Object value;
    }

    @Data
    @Schema(description = "提交答卷")
    public static class Submit {

        @Valid
        @Size(max = 100, message = "答案过多")
        private List<Answer> answers;
    }
}
