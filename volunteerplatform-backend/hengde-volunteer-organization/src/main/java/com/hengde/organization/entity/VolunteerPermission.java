package com.hengde.organization.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 志愿者↔权限点关联。超管全量替换式维护（镜像 {@link AdminPermission}，挂在志愿者域）。
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("volunteer_permission")
public class VolunteerPermission extends BaseEntity {

    /** 志愿者 volunteer.id */
    private Long volunteerId;

    /** 权限点 permission.id */
    private Long permissionId;
}
