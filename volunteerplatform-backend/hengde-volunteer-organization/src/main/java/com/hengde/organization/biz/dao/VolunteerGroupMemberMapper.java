package com.hengde.organization.biz.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.organization.biz.entity.VolunteerGroupMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VolunteerGroupMemberMapper extends BaseMapper<VolunteerGroupMember> {

    /**
     * 还在小组里（待审核 0 / 在册 1）、不是组长（role 1）、但账号已注销（{@code volunteer.status = 2}）的志愿者。
     * 供注销收尾的定时兜底用；组长不在此列——组长走了小组要有人接手，由管理员转移组长。一次至多 500 个。
     */
    @Select("SELECT DISTINCT gm.volunteer_id FROM volunteer_group_member gm "
            + "JOIN volunteer v ON v.id = gm.volunteer_id "
            + "WHERE gm.is_deleted = 0 AND gm.status IN (0, 1) AND gm.role <> 1 AND v.status = 2 LIMIT 500")
    List<Long> selectDeactivatedVolunteerIds();
}
