-- V86 按协会 2026-09-16 的部门分工，给现有后台账号授上 V85 的限制权限。
--
-- 【为什么要这一步】V85 之后开「限制参加活动」「限制发布社区」都要各自的权限点；不补授的话，
--         上线那一刻组织部、宣传部现有的账号会悄悄失去原本能开的那一档，而开单页面只会报「缺权限」。
--
-- 【怎么授】按 admin_user.department 那段文字（与投诉建议、部门流转同一口径，部门没有主数据表）：
--         组织部 → honor:sanction-activity；宣传部 → honor:sanction-community；秘书部 → honor:sanction-all；
--         理事会 → 三个全部（协会：理事会拥有全部限制权限）。超管走 * 通配，不必授。
--         这只是上线时的一次性补授：之后新建的账号仍在「子账号与权限」里逐个授权。
--
-- 【不撤销任何已有授权】此前已持有 honor:sanction-all 的其他部门账号保持不变，要收回由超管在授权页操作。
-- 【INSERT IGNORE】uk_admin_perm(admin_user_id, permission_id) 已存在（含曾被撤销的软删行）时跳过——
--         被撤销过的授权不在这里悄悄恢复。
INSERT IGNORE INTO admin_permission (admin_user_id, permission_id, create_time, is_deleted)
SELECT u.id, p.id, NOW(), 0
FROM admin_user u
JOIN permission p ON p.is_deleted = 0 AND (
       (p.code = 'honor:sanction-activity'  AND u.department IN ('组织部', '理事会'))
    OR (p.code = 'honor:sanction-community' AND u.department IN ('宣传部', '理事会'))
    OR (p.code = 'honor:sanction-all'       AND u.department IN ('秘书部', '理事会')))
WHERE u.is_deleted = 0
  AND u.is_super_admin = 0;
