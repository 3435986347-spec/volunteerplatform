package com.hengde.organization.constant;

/**
 * 权限点编码常量。与 {@code V2__organization_rbac.sql} 预置的 {@code permission.code} 一一对应，
 * 供 {@code @SaCheckPermission(value = ...)} 引用（须为编译期常量）。
 *
 * <p><b>注意：</b>{@link #USER_EDIT} 与 {@link #ORG_PERM_ASSIGN} 不在权限点表里、也不可分配，
 * 它们写死仅超管（{@code is_super_admin=1}）：前者碰实名敏感字段（R20），后者防子账号自助提权（R67）。
 * 这两个常量仅用于代码标注/检索，不要挂 {@code @SaCheckPermission}。</p>
 *
 * @author hengde
 */
public final class PermissionCode {

    private PermissionCode() {
    }

    // user 志愿者管理
    public static final String USER_MENU = "user:menu";
    public static final String USER_LIST = "user:list";
    public static final String USER_STATUS = "user:status";
    public static final String USER_DELETE = "user:delete";
    public static final String USER_EXPORT = "user:export";
    public static final String USER_PWD_RESET = "user:pwd-reset";

    // activity 活动管理
    public static final String ACTIVITY_MENU = "activity:menu";
    public static final String ACTIVITY_PUBLISH = "activity:publish";
    public static final String ACTIVITY_EDIT = "activity:edit";
    public static final String ACTIVITY_DELETE = "activity:delete";
    public static final String ACTIVITY_ENROLL_ADD = "activity:enroll-add";
    public static final String ACTIVITY_ENROLL_EXPORT = "activity:enroll-export";
    public static final String ACTIVITY_ENROLL_DELETE = "activity:enroll-delete";
    public static final String ACTIVITY_ENROLL_AUDIT = "activity:enroll-audit";

    // organization 组织
    public static final String ORG_SUB_ACCOUNT = "org:sub-account";
    public static final String ORG_GROUP_MANAGE = "org:group-manage";
    public static final String ORG_GROUP_AUDIT = "org:group-audit";
    public static final String ORG_SQUAD_MANAGE = "org:squad-manage";
    public static final String ORG_SQUAD_AUDIT = "org:squad-audit";

    // publicity 公示
    public static final String PUB_BANNER = "pub:banner";
    public static final String PUB_ANNOUNCEMENT = "pub:announcement";
    public static final String PUB_FILE = "pub:file";

    // data 数据看板
    public static final String DATA_DASHBOARD = "data:dashboard";

    // ⚠️ 仅超管，不可分配、不挂注解（在 service 里手写 is_super_admin 校验）
    public static final String USER_EDIT = "user:edit";
    public static final String ORG_PERM_ASSIGN = "org:perm-assign";

    /** 超管万能权限码（Sa-Token 通配，匹配所有 @SaCheckPermission） */
    public static final String WILDCARD = "*";
}
