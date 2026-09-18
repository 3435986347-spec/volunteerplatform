package com.hengde.organization.form.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.organization.form.entity.OrgFormQuestion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问卷题目。
 *
 * @author hengde
 */
@Mapper
public interface OrgFormQuestionMapper extends BaseMapper<OrgFormQuestion> {
}
