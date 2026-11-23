package com.hengde.organization.biz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("organization_structure_node")
public class OrganizationStructureNode extends BaseEntity {
    private Long parentId;
    private String name;
    private String title;
    private Integer sort;
}
