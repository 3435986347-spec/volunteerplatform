package com.hengde.organization.form.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 问卷题目（V63）。题型见 {@code QuestionType}；选项与配置存 JSON，由 {@code FormQuestionCodec} 读写。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("org_form_question")
public class OrgFormQuestion extends BaseEntity {

    private Long formId;
    private Integer sort;
    private Integer questionType;
    private String title;
    private String description;
    private Integer required;
    private String optionsJson;
    private String configJson;
}
