package com.hengde.data.complaint.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 投诉建议处理进度（V65，只追加）。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_complaint_log")
public class DataComplaintLog extends BaseEntity {

    private Long complaintId;
    private Integer action;
    private String fromDepartment;
    private String toDepartment;
    private String content;
    /** 1 志愿者可见 / 0 仅后台 */
    private Integer visible;
    private Integer operatorType;
    private Long operatorId;
}
