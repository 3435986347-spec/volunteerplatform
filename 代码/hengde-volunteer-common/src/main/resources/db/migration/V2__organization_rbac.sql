-- V2 organization 域 RBAC：子账号细粒度权限（对应 V1 R66/R67）
-- 最小 RBAC：权限点表 + 子账号↔权限点关联表，不引入 role 中间层（V1 部门少、不复用）。
-- 鉴权：功能/审核权限走 Sa-Token @SaCheckPermission（数据由 organization 的 StpInterface 喂）；
--       超管 is_super_admin=1 由 StpInterface 直接返回 * 万能码放行。
-- 两个高危点不进本表、写死仅超管：org:perm-assign（防自助提权）、user:edit（碰实名敏感字段）。

-- ============================================================
-- 权限点（系统预置，前端只读展示给超管勾选）
-- type：1=菜单 2=操作 3=审核（对应 R66 菜单/数据/审核三类分离；数据权限行级过滤不在此表，单独手写）
-- ============================================================
CREATE TABLE permission (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    code        VARCHAR(64) NOT NULL COMMENT '权限点编码，如 volunteer:list / enrollment:approve',
    name        VARCHAR(64) NOT NULL COMMENT '中文名',
    module      VARCHAR(32) NOT NULL COMMENT '所属菜单/模块 user/activity/org/publicity/data',
    type        TINYINT     NOT NULL COMMENT '1菜单 2操作 3审核',
    sort        INT         NOT NULL DEFAULT 0 COMMENT '展示排序',
    create_time DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    is_deleted  TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '权限点（系统预置）';

-- 子账号↔权限点（超管逐个分配；全量替换式维护）
CREATE TABLE admin_permission (
    id            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    admin_user_id BIGINT   NOT NULL COMMENT '子账号 admin_user.id',
    permission_id BIGINT   NOT NULL COMMENT '权限点 permission.id',
    create_time   DATETIME DEFAULT NULL COMMENT '创建时间',
    update_time   DATETIME DEFAULT NULL COMMENT '更新时间',
    is_deleted    TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_perm (admin_user_id, permission_id),
    KEY idx_admin (admin_user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '子账号-权限点关联';

-- ============================================================
-- 预置可分配权限点（23 个）。user:edit 与 org:perm-assign 故意不在此 —— 它们写死仅超管。
-- ============================================================
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
-- user 志愿者管理
('user:menu',               '志愿者管理菜单',   'user',      1, 10, NOW(), 0),
('user:list',               '志愿者查看',       'user',      2, 11, NOW(), 0),
('user:status',             '暂停/恢复账号',    'user',      2, 12, NOW(), 0),
('user:delete',             '删除志愿者',       'user',      2, 13, NOW(), 0),
('user:export',             '导出志愿者',       'user',      2, 14, NOW(), 0),
('user:pwd-reset',          '重置志愿者密码',   'user',      2, 15, NOW(), 0),
-- activity 活动管理
('activity:menu',           '活动管理菜单',     'activity',  1, 20, NOW(), 0),
('activity:publish',        '发布/复制活动',    'activity',  2, 21, NOW(), 0),
('activity:edit',           '修改活动',         'activity',  2, 22, NOW(), 0),
('activity:delete',         '删除活动',         'activity',  2, 23, NOW(), 0),
('activity:enroll-add',     '手动新增报名',     'activity',  2, 24, NOW(), 0),
('activity:enroll-export',  '导出报名名单',     'activity',  2, 25, NOW(), 0),
('activity:enroll-delete',  '删除报名记录',     'activity',  2, 26, NOW(), 0),
('activity:enroll-audit',   '报名审核',         'activity',  3, 27, NOW(), 0),
-- organization 组织
('org:sub-account',         '子账号管理',       'org',       2, 30, NOW(), 0),
('org:group-manage',        '小组管理',         'org',       2, 31, NOW(), 0),
('org:group-audit',         '建组审核',         'org',       3, 32, NOW(), 0),
('org:squad-manage',        '分队管理',         'org',       2, 33, NOW(), 0),
('org:squad-audit',         '分队加入审核',     'org',       3, 34, NOW(), 0),
-- publicity 公示
('pub:banner',              '轮播图管理',       'publicity', 2, 40, NOW(), 0),
('pub:announcement',        '公告管理',         'publicity', 2, 41, NOW(), 0),
('pub:file',                '文件管理',         'publicity', 2, 42, NOW(), 0),
-- data 数据看板
('data:dashboard',          '后台数据看板',     'data',      1, 50, NOW(), 0);
