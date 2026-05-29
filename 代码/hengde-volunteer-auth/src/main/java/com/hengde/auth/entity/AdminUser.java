package com.hengde.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hengde.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 后台账号（管理团队）实体。
 *
 * <p>账号+密码登录。细粒度权限（菜单/数据/审核）由 organization 域的 RBAC 表挂接，不在此。</p>
 *
 * @author hengde
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("admin_user")
public class AdminUser extends BaseEntity {

    /** 登录账号 */
    private String username;

    /** 密码（BCrypt 密文，见 {@link com.hengde.common.utils.PasswordUtil}） */
    private String password;

    /** 姓名 */
    private String realName;

    /** 手机号（明文，找回密码用） */
    private String phone;

    /** 部门 */
    private String department;

    /** 是否超管 1是/0否 */
    private Integer isSuperAdmin;

    /** 状态 0启用/1禁用 */
    private Integer status;

    /** 上次登录时间 */
    private LocalDateTime lastLoginTime;
}
