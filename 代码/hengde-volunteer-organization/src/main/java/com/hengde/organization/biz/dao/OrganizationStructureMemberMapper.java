package com.hengde.organization.biz.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.organization.biz.entity.OrganizationStructureMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 组织架构里的人。
 *
 * @author hengde
 */
@Mapper
public interface OrganizationStructureMemberMapper extends BaseMapper<OrganizationStructureMember> {

    /**
     * 还挂在架构里、但账号已注销（{@code volunteer.status = 2}）的志愿者。供注销收尾的定时兜底用——
     * 注销事件是进程内、不持久的，订阅方一次失败就要靠这里补上。一次至多 500 个。
     */
    @Select("SELECT DISTINCT m.volunteer_id FROM organization_structure_member m "
            + "JOIN volunteer v ON v.id = m.volunteer_id "
            + "WHERE m.is_deleted = 0 AND v.status = 2 LIMIT 500")
    List<Long> selectDeactivatedVolunteerIds();
}
