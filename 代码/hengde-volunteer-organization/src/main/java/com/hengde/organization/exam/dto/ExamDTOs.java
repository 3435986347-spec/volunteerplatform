package com.hengde.organization.exam.dto;

import com.hengde.organization.form.dto.FormDTOs;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 临时负责人考试入参（V4 临时负责人考试批）。题型与答案格式与问卷共用（V4规划 D3），规则在 {@code ExamScoring}。
 *
 * @author hengde
 */
public final class ExamDTOs {

    private ExamDTOs() {
    }

    @Data
    @Schema(description = "保存试卷（新建 / 修改草稿）：题目整批提交，按提交顺序编号")
    public static class PaperSave {

        @NotBlank(message = "请填写试卷名称")
        @Size(max = 100, message = "试卷名称不超过 100 字")
        private String title;

        @Size(max = 1000, message = "试卷说明不超过 1000 字")
        private String description;

        @NotNull(message = "请填写及格线")
        @Min(value = 1, message = "及格线至少 1 分")
        @Schema(description = "及格线：总分达到即获得活动临时负责人资格")
        private Integer passScore;

        @Min(value = 1, message = "资格有效期至少 1 个月")
        @Max(value = 120, message = "资格有效期最多 120 个月")
        @Schema(description = "资格有效期（月）；不填＝长期有效（Q6）")
        private Integer qualificationMonths;

        @Valid
        @NotEmpty(message = "试卷至少要有一道题")
        @Size(max = 100, message = "一份试卷最多 100 道题")
        private List<QuestionSave> questions;
    }

    @Data
    @Schema(description = "一道试题")
    public static class QuestionSave {

        @NotNull(message = "请选择题型")
        @Schema(description = "1单选/2多选/3判断/4填空/5简答")
        private Integer type;

        @NotBlank(message = "请填写题目")
        @Size(max = 500, message = "题目不超过 500 字")
        private String title;

        @Size(max = 512, message = "题目说明不超过 512 字")
        private String description;

        @Schema(description = "选项文字（单选 / 多选必填，2–50 个）；编号 A、B、C… 由服务端分配")
        private List<String> options;

        @Schema(description = "填空 / 简答：作答字数上限（填空默认 100、简答默认 1000）")
        private Integer maxLength;

        @NotNull(message = "请填写分值")
        @Min(value = 1, message = "分值至少 1 分")
        @Max(value = 100, message = "一道题最多 100 分")
        private Integer score;

        @Schema(description = "标准答案——单选：\"A\"；多选：[\"A\",\"C\"]（全对才得分）；判断：true/false（三者必填，交卷自动判分）。"
                + "填空 / 简答：参考答案文字（选填，只给阅卷人看）")
        private Object answer;
    }

    @Data
    @Schema(description = "交卷")
    public static class Submit {

        @NotNull(message = "缺少试卷 id")
        @Schema(description = "作答的试卷 id（打开考试时拿到的那份；交卷时已换了试卷会被拒绝）")
        private Long paperId;

        @Valid
        @Size(max = 100, message = "答案过多")
        @Schema(description = "答案，格式与问卷相同；没答的题不传或传空，按 0 分算")
        private List<FormDTOs.Answer> answers;
    }

    @Data
    @Schema(description = "阅卷：给每一道主观题打分")
    public static class Grade {

        @Valid
        @NotNull(message = "请给主观题打分")
        private List<QuestionScore> scores;

        @Size(max = 255, message = "阅卷备注不超过 255 字")
        private String note;
    }

    @Data
    @Schema(description = "一道主观题的得分")
    public static class QuestionScore {

        @NotNull(message = "缺少题目 id")
        private Long questionId;

        @NotNull(message = "请填写得分")
        @Min(value = 0, message = "得分不能为负")
        private Integer score;
    }

    @Data
    @Schema(description = "撤销临时负责人资格")
    public static class Revoke {

        @NotBlank(message = "请填写撤销原因")
        @Size(max = 255, message = "撤销原因不超过 255 字")
        private String reason;
    }
}
