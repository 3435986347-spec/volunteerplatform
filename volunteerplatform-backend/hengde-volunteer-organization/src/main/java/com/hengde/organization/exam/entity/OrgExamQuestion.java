package com.hengde.organization.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 临时负责人考试试题（V75）。 */
@Getter
@Setter
@TableName("org_exam_question")
public class OrgExamQuestion extends BaseEntity {
    private Long paperId;
    private Integer sort;
    private Integer questionType;
    private String title;
    private String description;
    private String optionsJson;
    private String configJson;
    private String answerJson;
    private Integer score;
}
