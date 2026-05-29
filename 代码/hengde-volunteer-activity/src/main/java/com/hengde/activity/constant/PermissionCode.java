package com.hengde.activity.constant;

/**
 * activity 域权限点编码（与 V1 迁移 permission 表预置数据一致）。
 *
 * <p>管理端接口用 {@code @SaCheckPermission(value=..., type="admin")} 引用；
 * 权限数据由 organization 的 StpInterface 提供，超管走 {@code *} 万能码放行。</p>
 *
 * @author hengde
 */
public final class PermissionCode {

    private PermissionCode() {
    }

    /** 活动管理菜单 */
    public static final String ACTIVITY_MENU = "activity:menu";

    /** 发布/复制活动 */
    public static final String ACTIVITY_PUBLISH = "activity:publish";

    /** 修改活动 */
    public static final String ACTIVITY_EDIT = "activity:edit";

    /** 删除活动 */
    public static final String ACTIVITY_DELETE = "activity:delete";

    /** 查看报名名单（含志愿者明文手机号，故独立于菜单权限单独门控） */
    public static final String ACTIVITY_ENROLL_VIEW = "activity:enroll-view";

    /** 手动新增报名 */
    public static final String ACTIVITY_ENROLL_ADD = "activity:enroll-add";

    /** 导出报名名单 */
    public static final String ACTIVITY_ENROLL_EXPORT = "activity:enroll-export";

    /** 删除报名记录 */
    public static final String ACTIVITY_ENROLL_DELETE = "activity:enroll-delete";

    /** 报名审核 */
    public static final String ACTIVITY_ENROLL_AUDIT = "activity:enroll-audit";
}
