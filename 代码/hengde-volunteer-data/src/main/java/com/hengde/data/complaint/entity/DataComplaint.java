package com.hengde.data.complaint.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 投诉建议工单（V65）。状态与类型见 {@code ComplaintFlow}。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_complaint")
public class DataComplaint extends BaseEntity {

    private String complaintNo;
    private Long volunteerId;
    private Integer complaintType;
    private String content;
    /** 逗号分隔 */
    private String images;
    private Long formSubmissionId;
    private Integer status;
    private String currentDepartment;
    private Integer transferCount;
    private Long acceptBy;
    private LocalDateTime acceptTime;
    private String replyContent;
    private Long replyBy;
    private String replyDepartment;
    private LocalDateTime replyTime;
}
