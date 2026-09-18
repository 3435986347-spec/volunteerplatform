package com.hengde.organization.exam.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.organization.exam.entity.OrgExamPaper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 临时负责人考试试卷（V75）。 */
public interface OrgExamPaperMapper extends BaseMapper<OrgExamPaper> {

    /**
     * 交卷时锁住试卷行（<b>当前读 + 共享锁</b>，与问卷提交同形）：读完不改这一行，只要求「停止」与「交卷」串行——
     * 停止是对这一行的 UPDATE，要等交卷事务放掉共享锁；反过来交卷也看得到刚提交的停止，不会收进一份已停止试卷的答卷。
     */
    @Select("SELECT * FROM org_exam_paper WHERE id = #{id} AND is_deleted = 0 FOR SHARE")
    OrgExamPaper selectByIdForShare(@Param("id") Long id);
}
