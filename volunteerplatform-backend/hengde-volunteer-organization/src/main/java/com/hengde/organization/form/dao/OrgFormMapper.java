package com.hengde.organization.form.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.organization.form.entity.OrgForm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 问卷。
 *
 * @author hengde
 */
@Mapper
public interface OrgFormMapper extends BaseMapper<OrgForm> {

    /**
     * 提交答卷时锁住问卷行（<b>当前读 + 共享锁</b>）：读完不改这一行，只要求「停止收集」与「提交」串行——
     * 停止是对这一行的 UPDATE，要等提交事务放掉共享锁；反过来提交也看得到刚提交的停止，不会收进一份已停止问卷的答卷。
     */
    @Select("SELECT * FROM org_form WHERE id = #{id} AND is_deleted = 0 FOR SHARE")
    OrgForm selectByIdForShare(@Param("id") Long id);
}
