package com.hengde.organization.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权限点（系统预置，前端只读）。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("permission")
public class Permission extends BaseEntity {

    /** 权限点编码，如 user:list */
    private String code;

    /** 中文名 */
    private String name;

    /** 所属模块 user/activity/org/publicity/data */
    private String module;

    /** 类型 1菜单/2操作/3审核 */
    private Integer type;

    /** 展示排序 */
    private Integer sort;
}
