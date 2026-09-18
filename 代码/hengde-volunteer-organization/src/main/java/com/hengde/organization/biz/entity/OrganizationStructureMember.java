package com.hengde.organization.biz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 组织架构里的人（V69，Row 6）：哪个节点（部门）、什么职位。一个人在架构里至多一个位置。
 *
 * @author hengde
 */
@Getter
@Setter
@TableName("organization_structure_member")
public class OrganizationStructureMember extends BaseEntity {

    private Long nodeId;
    private Long volunteerId;
    private String position;
    private Integer sort;
}
