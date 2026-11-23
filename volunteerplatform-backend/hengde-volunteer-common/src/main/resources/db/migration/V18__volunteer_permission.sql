-- V18 志愿者端 RBAC：把权限点体系扩到志愿者域（小程序里「管理团队」志愿者凭权限管理/发布活动）。
-- 背景：原 RBAC 权限只挂子账号（admin_permission ↔ admin_user），AdminStpInterface 对志愿者域
--   （loginType=login）直接返回空集。本迁移新增 volunteer_permission（volunteer ↔ permission），
--   并给 permission 加 volunteer_grantable 列标记「哪些点可授权给志愿者」（本期=活动域子集，除 activity:menu）。
-- 鉴权：StpInterface 对志愿者域查 volunteer_permission 喂 @SaCheckPermission；停用志愿者一律空集。

-- 志愿者↔权限点（超管逐个分配；全量替换式维护，结构镜像 admin_permission）
CREATE TABLE volunteer_permission (
    id            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    volunteer_id  BIGINT   NOT NULL COMMENT '志愿者 volunteer.id',
    permission_id BIGINT   NOT NULL COMMENT '权限点 permission.id',
    create_time   DATETIME DEFAULT NULL COMMENT '创建时间',
    update_time   DATETIME DEFAULT NULL COMMENT '更新时间',
    is_deleted    TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_vol_perm (volunteer_id, permission_id),
    KEY idx_volunteer (volunteer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '志愿者-权限点关联';

-- 权限点加「是否可授权给志愿者」白名单标记
ALTER TABLE permission ADD COLUMN volunteer_grantable TINYINT NOT NULL DEFAULT 0
    COMMENT '是否可授权给志愿者 0否/1是（志愿者端 RBAC 白名单）';

-- 本期开放活动域子集给志愿者（除「活动管理菜单」activity:menu 是后台菜单点，志愿者端不适用）
UPDATE permission SET volunteer_grantable = 1
    WHERE module = 'activity' AND code <> 'activity:menu';
