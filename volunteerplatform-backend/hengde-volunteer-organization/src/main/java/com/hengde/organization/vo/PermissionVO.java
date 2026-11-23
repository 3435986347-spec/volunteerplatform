package com.hengde.organization.vo;

import lombok.Data;

/**
 * 可分配权限点出参（供超管勾选）。
 *
 * @author hengde
 */
@Data
public class PermissionVO {

    private Long id;
    private String code;
    private String name;

    /** 所属模块 user/activity/org/publicity/data */
    private String module;

    /** 类型 1菜单/2操作/3审核 */
    private Integer type;

    private Integer sort;
}
