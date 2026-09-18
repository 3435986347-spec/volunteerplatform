package com.hengde.data.complaint.vo;

import com.hengde.organization.form.vo.FormVOs;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 投诉建议出参（V4 投诉建议批）。
 *
 * @author hengde
 */
public final class ComplaintVOs {

    private ComplaintVOs() {
    }

    @Data
    @Schema(description = "投诉建议工单")
    public static class Complaint {
        private Long id;
        private String complaintNo;
        private Integer type;
        private String typeLabel;
        private String content;
        private List<String> images = new ArrayList<>();
        @Schema(description = "0待受理/1处理中/2已办结")
        private Integer status;
        private String statusLabel;
        private String currentDepartment;
        private Integer transferCount;
        private LocalDateTime acceptTime;
        @Schema(description = "答复（已办结才有）")
        private String replyContent;
        private String replyDepartment;
        private LocalDateTime replyTime;
        private LocalDateTime createTime;
        @Schema(description = "处理进度（详情才有；志愿者端只含对他可见的几步）")
        private List<Progress> progress;
        @Schema(description = "随工单提交的问卷答卷逐题答案（详情才有）")
        private List<FormVOs.AnswerView> formAnswers;
        @Schema(description = "提交人（仅管理端）")
        private Long volunteerId;
        private String volunteerName;
        @Schema(description = "提交人电话（仅管理端，明文——处理人要联系他）")
        private String volunteerPhone;
        @Schema(description = "当前受理人（仅管理端）")
        private String acceptByName;
    }

    @Data
    @Schema(description = "一步处理进度")
    public static class Progress {
        private Integer action;
        private String actionLabel;
        @Schema(description = "给人看的一句话：「已提交，由监察部受理」「已转交宣传部处理」…")
        private String description;
        private String fromDepartment;
        private String toDepartment;
        @Schema(description = "答复内容（志愿者端只有答复这一步带内容；管理端另含流转理由与内部备注）")
        private String content;
        @Schema(description = "是否对志愿者可见（仅管理端）")
        private Boolean visible;
        @Schema(description = "操作人（仅管理端）")
        private String operatorName;
        private LocalDateTime time;
    }
}
