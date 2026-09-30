-- V92 按部门补授处置三档权限：放宽到「部门文字前后带空格」也认。
--
-- 【为什么】V86 按 admin_user.department 与「组织部 / 宣传部 / 秘书部 / 理事会」逐字相等匹配；
--         部门字段是后台手填的，前后多一个空格就对不上，那个账号会悄悄失去原本能开的档位
--         （V85 之前开单权自带「限制参加活动」「限制发布社区」）。这里用 TRIM 再补一遍。
-- 【仍然对不上的】写错字、没填部门的账号补不到，启动时由 organization 的 SanctionPermissionAudit 列进 WARN 日志，
--         超管在「子账号与权限」里手工补。
-- 【幂等】INSERT IGNORE：V86 已授过的跳过；不撤任何已有授权。与 V86 同口径：超管走 * 通配不授、已删账号不授。
INSERT IGNORE INTO admin_permission (admin_user_id, permission_id, create_time, is_deleted)
SELECT u.id, p.id, NOW(), 0
FROM admin_user u
JOIN permission p ON p.is_deleted = 0 AND (
       (p.code = 'honor:sanction-activity'  AND TRIM(u.department) IN ('组织部', '理事会'))
    OR (p.code = 'honor:sanction-community' AND TRIM(u.department) IN ('宣传部', '理事会'))
    OR (p.code = 'honor:sanction-all'       AND TRIM(u.department) IN ('秘书部', '理事会'))
    OR (p.code = 'user:restore'             AND TRIM(u.department) = '理事会'))
WHERE u.is_deleted = 0
  AND u.is_super_admin = 0;
