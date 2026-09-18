package com.hengde.organization.biz.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.organization.biz.entity.OrganizationStructureMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * 组织架构里的人。
 *
 * @author hengde
 */
@Mapper
public interface OrganizationStructureMemberMapper extends BaseMapper<OrganizationStructureMember> {
}
