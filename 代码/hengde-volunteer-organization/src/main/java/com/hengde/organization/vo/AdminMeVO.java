package com.hengde.organization.vo;

import lombok.Data;

import java.util.List;

/**
 * 当前管理员「我是谁 + 我有哪些权限」出参，供 {@code GET /a/auth/me}。
 *
 * <p>前端登录后据此渲染：用 {@link #superAdmin}/{@link #permissionCodes} 决定菜单与按钮显隐，
 * 用 {@link #realName}/{@link #department} 显示账号与水印。超管的 {@code permissionCodes} 为
 * 单元素 {@code ["*"]}（万能码），前端可据此直接放行全部入口。</p>
 *
 * @author hengde
 */
@Data
public class AdminMeVO {

    private Long adminId;
    private String username;
    private String realName;
    private String department;

    /** 是否超管：true → 拥有全部权限（permissionCodes 为 ["*"]） */
    private Boolean superAdmin;

    /** 已分配权限点编码；超管为 ["*"] */
    private List<String> permissionCodes;
}
