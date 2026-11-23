-- V91 按协会 2026-09-16「账号恢复 → 理事会」给现有理事会账号授上 user:restore。
-- 与 V86 同一口径：按 admin_user.department 文字、超管与已删账号不授、曾被撤销的授权（软删行）不恢复；
-- 这只是上线时的一次性补授，之后新建的账号仍在「子账号与权限」里逐个授权。
INSERT IGNORE INTO admin_permission (admin_user_id, permission_id, create_time, is_deleted)
SELECT u.id, p.id, NOW(), 0
FROM admin_user u
JOIN permission p ON p.code = 'user:restore' AND p.is_deleted = 0
WHERE u.department = '理事会'
  AND u.is_deleted = 0
  AND u.is_super_admin = 0;
