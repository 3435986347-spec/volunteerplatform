package com.hengde.organization.form.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 问卷出参（V4 问卷引擎批）。
 *
 * @author hengde
 */
public final class FormVOs {

    private FormVOs() {
    }

    @Data
    @Schema(description = "问卷")
    public static class Form {
        private Long id;
        private Integer scene;
        private String sceneLabel;
        private String title;
        private String description;
        @Schema(description = "0草稿/1收集中/2已停止")
        private Integer status;
        private String statusLabel;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Boolean singleSubmit;
        private Boolean requireRegistered;
        private LocalDateTime publishTime;
        private LocalDateTime closeTime;
        private LocalDateTime createTime;
        private Integer questionCount;
        @Schema(description = "答卷数（仅管理端）")
        private Long submissionCount;
        @Schema(description = "题目（详情才有）")
        private List<Question> questions;
        @Schema(description = "我是否已经提交过（仅志愿者端）")
        private Boolean submitted;
    }

    @Data
    @Schema(description = "题目")
    public static class Question {
        private Long id;
        @Schema(description = "题号，从 1 起")
        private Integer sort;
        private Integer type;
        private String typeLabel;
        private String title;
        private String description;
        private Boolean required;
        private List<Option> options;
        private Integer maxLength;
        private Integer minSelect;
        private Integer maxSelect;
        private Integer maxFiles;
        private String minDate;
        private String maxDate;
    }

    @Data
    @Schema(description = "选项")
    public static class Option {
        private String key;
        private String label;

        public Option() {
        }

        public Option(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    @Data
    @Schema(description = "答卷")
    public static class Submission {
        private Long id;
        private Long formId;
        private String formTitle;
        private Integer scene;
        private LocalDateTime submitTime;
        @Schema(description = "填写人（仅管理端）")
        private Long volunteerId;
        private String volunteerName;
        @Schema(description = "填写人电话（仅管理端，明文——协会要联系他）")
        private String volunteerPhone;
        private List<AnswerView> answers = new ArrayList<>();
    }

    @Data
    @Schema(description = "一道题的答案（带题面，便于展示）")
    public static class AnswerView {
        private Long questionId;
        private Integer sort;
        private String title;
        private Integer type;
        private String typeLabel;
        @Schema(description = "规范化后的原值（选项编号 / 布尔 / 文字 / URL 数组 / 日期）")
        private Object value;
        @Schema(description = "给人看的文字：选项换成选项文字、判断换成「是 / 否」、文件每行一个 URL")
        private String display;
    }
}
