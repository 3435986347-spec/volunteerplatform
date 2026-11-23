package com.hengde.organization.exam.vo;

import com.hengde.organization.form.vo.FormVOs;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 临时负责人考试出参（V4 临时负责人考试批）。
 *
 * @author hengde
 */
public final class ExamVOs {

    private ExamVOs() {
    }

    @Data
    @Schema(description = "试卷")
    public static class Paper {
        private Long id;
        private String title;
        private String description;
        private Integer passScore;
        private Integer totalScore;
        @Schema(description = "资格有效期（月），空＝长期有效")
        private Integer qualificationMonths;
        @Schema(description = "0草稿/1开放中/2已停止")
        private Integer status;
        private String statusLabel;
        private Integer questionCount;
        private LocalDateTime createTime;
        @Schema(description = "答卷数（仅管理端列表）")
        private Long attemptCount;
        @Schema(description = "题目（详情才有）")
        private List<Question> questions;
    }

    @Data
    @Schema(description = "试题")
    public static class Question {
        private Long id;
        private Integer sort;
        private Integer type;
        private String typeLabel;
        private String title;
        private String description;
        private List<FormVOs.Option> options;
        private Integer maxLength;
        private Integer score;
        @Schema(description = "主观题（填空 / 简答，人工阅卷）")
        private Boolean subjective;
        @Schema(description = "标准答案 / 参考答案（仅管理端）")
        private Object answer;
        @Schema(description = "标准答案的文字（仅管理端）")
        private String answerDisplay;
    }

    @Data
    @Schema(description = "我的考试（志愿者端）")
    public static class MyExam {
        @Schema(description = "当前开放的试卷（不含答案）；没有开放的试卷时为空")
        private Paper paper;
        @Schema(description = "我现在是不是活动临时负责人")
        private Boolean tempLeader;
        @Schema(description = "资格到期时间（长期有效为空）")
        private LocalDateTime qualificationExpireTime;
        @Schema(description = "我有一份待阅卷的答卷")
        private Boolean hasPendingAttempt;
        @Schema(description = "现在能不能考")
        private Boolean canTake;
        @Schema(description = "不能考的原因")
        private String reason;
    }

    @Data
    @Schema(description = "答卷")
    public static class Attempt {
        private Long id;
        private Long paperId;
        private String paperTitle;
        private Long volunteerId;
        @Schema(description = "考生姓名（仅管理端）")
        private String volunteerName;
        private Integer passScore;
        private Integer paperTotalScore;
        private Integer objectiveScore;
        private Integer subjectiveScore;
        private Integer totalScore;
        @Schema(description = "是否及格（出分后）")
        private Boolean passed;
        @Schema(description = "1待阅卷/2已出分")
        private Integer status;
        private String statusLabel;
        private String gradeNote;
        private String gradedByName;
        private LocalDateTime gradedTime;
        private LocalDateTime submitTime;
        @Schema(description = "逐题（详情才有）")
        private List<AnswerRow> answers;
    }

    @Data
    @Schema(description = "一道题的作答与得分")
    public static class AnswerRow {
        private Long questionId;
        private Integer sort;
        private Integer type;
        private String typeLabel;
        private String title;
        private Integer fullScore;
        private Boolean subjective;
        private Object value;
        private String display;
        @Schema(description = "得分；主观题未阅卷时为空")
        private Integer score;
        @Schema(description = "标准答案 / 参考答案文字（仅管理端）")
        private String answerDisplay;
    }

    @Data
    @Schema(description = "临时负责人资格")
    public static class Qualification {
        private Long id;
        private Long volunteerId;
        private String volunteerName;
        @Schema(description = "手机号（管理端明文）")
        private String phone;
        private Long attemptId;
        private Integer attemptScore;
        private String paperTitle;
        private LocalDateTime grantedTime;
        private LocalDateTime expireTime;
        @Schema(description = "1有效/2已到期/3已撤销（按时间现算）")
        private Integer status;
        private String statusLabel;
        private String revokedByName;
        private LocalDateTime revokedTime;
        private String revokeReason;
    }
}
