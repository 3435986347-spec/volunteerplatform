package com.hengde.organization.form.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 问卷答卷（V63）。{@code scene} / {@code singleSubmit} 是提交时的快照。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("org_form_submission")
public class OrgFormSubmission extends BaseEntity {

    private Long formId;
    private Integer scene;
    private Long volunteerId;
    private Integer singleSubmit;
    private String answersJson;
    private LocalDateTime submitTime;
}
