package com.hengde.organization.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 子账号↔权限点关联。超管全量替换式维护。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("admin_permission")
public class AdminPermission extends BaseEntity {

    /** 子账号 admin_user.id */
    private Long adminUserId;

    /** 权限点 permission.id */
    private Long permissionId;
}
