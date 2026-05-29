package com.hengde.organization.dto;

import lombok.Data;

import java.util.List;

/**
 * 全量替换子账号权限入参。{@code permissionIds} 为空或 null 表示清空该子账号全部权限。
 *
 * @author hengde
 */
@Data
public class AssignPermissionsDTO {

    /** 权限点 id 集合（permission.id） */
    private List<Long> permissionIds;
}
