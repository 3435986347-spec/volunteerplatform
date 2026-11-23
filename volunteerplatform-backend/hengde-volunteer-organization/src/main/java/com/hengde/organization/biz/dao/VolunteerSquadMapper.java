package com.hengde.organization.biz.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.organization.biz.entity.VolunteerSquad;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VolunteerSquadMapper extends BaseMapper<VolunteerSquad> {

    /**
     * 锁住分队行（排他）。<b>只能在事务内调用，且要作为事务的第一条语句。</b>
     *
     * <p>批准加入有两条路（部门在后台、分队负责人在小程序），人数上限是「先数一数、再写归属」——
     * 两条路同时批同一个分队的两份申请，各数各的都没到上限，一起写进去就超员了。
     * 锁住分队行把同一分队的批准排成一队；放在事务第一条，是为了让之后的读在拿到锁之后才建立读视图，
     * 看得见前一个批准已经提交的归属（REPEATABLE READ 下读视图在第一次普通读时定死）。</p>
     */
    @Select("SELECT * FROM volunteer_squad WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    VolunteerSquad selectByIdForUpdate(@Param("id") Long id);
}
