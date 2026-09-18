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
    /** 志愿者「管理团队」标记手动开关（V12；积分 ×1.2 倍率通道，预留的报名管理团队审批将回写同一标记） */
    public static final String ORG_MANAGER_FLAG = "org:manager-flag";
    /** 问卷管理：建 / 改草稿 / 发布 / 停止 / 复制 / 删草稿（V63，V4 问卷引擎批） */
    public static final String ORG_FORM = "org:form";
    /** 问卷答卷查看与导出（V63）——答卷里有填写人的手机号，与「能建问卷」分开授权 */
    public static final String ORG_FORM_DATA = "org:form-data";
    /** 个人中心内容设置：我的保险 / 联系客服（V66，V4 个人中心补全批；Row 42 / Row 48「后台设置」） */
    public static final String USER_CENTER_CONTENT = "user:center-content";
    /** 组织架构维护：节点增删改、放人挪人（V69，V4 组织架构维护批；Row 6「均有最高权限操作」，超管 * 通配） */
    public static final String ORG_STRUCTURE = "org:structure";
    /** 临时负责人考试试题管理：试卷 / 题目 / 开放停止 / 复制（V75，V4 临时负责人考试批；Row 14 F「管理考试试题」） */
    public static final String ORG_EXAM = "org:exam";
    /** 临时负责人考试阅卷：主观题人工判分（V75；Row 45 F「填空题等主观题需要人工审核」） */
    public static final String ORG_EXAM_GRADE = "org:exam-grade";
    /** 活动临时负责人管理：名单 / 撤销资格 / 批量导出（V75；Row 14 F） */
    public static final String ORG_TEMP_LEADER = "org:temp-leader";

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
