package com.hengde.organization.exam.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 临时负责人考试答卷（V75）。 */
@Getter
@Setter
@TableName("org_exam_attempt")
public class OrgExamAttempt {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long paperId;
    private Long volunteerId;
    private String answersJson;
    private Integer objectiveScore;
    private String subjectiveJson;
    private Integer subjectiveScore;
    private Integer totalScore;
    private Integer passed;
    private Integer status;
    private String gradeNote;
    private Long gradedBy;
    private LocalDateTime gradedTime;
    private LocalDateTime submitTime;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long activePendingKey;
}
