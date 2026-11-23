package com.hengde.organization.exam.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 临时负责人考试试卷（V75）。 */
@Getter
@Setter
@TableName("org_exam_paper")
public class OrgExamPaper extends BaseEntity {
    private String title;
    private String description;
    private Integer passScore;
    private Integer totalScore;
    private Integer qualificationMonths;
    private Integer status;
    private Long createdBy;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Integer activeOpenKey;
}
