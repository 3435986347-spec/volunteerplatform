-- V85 处罚的三档限制按部门各一个权限点。
--
-- 【依据】协会 2026-09-16：限制参加活动 → 组织部 / 限制发布社区 → 宣传部 / 拒绝使用本程序 → 秘书部 /
--         账号恢复 → 理事会；理事会拥有全部限制权限。监察部与分队管理部一年前已撤销。
--         需求表 Row 73 原写「可由该部门负责的同学限制其使用……监察部拥有全部限制能力」，以协会答复为准。
--
-- 【此前】V36 只拆出了最重的一档（honor:sanction-all），「限制参加活动」「限制发布社区」跟着开单权
--         （honor:reward-punish）走，任何能开单的人都能开这两档。
--
-- 【取名】沿用 honor:sanction-all 的码（改码会让已授权的账号全部失效），名称改为它现在的含义「拒绝使用本程序」。
--         三个点互不蕴含：秘书部能开最重的一档，不等于它能开「限制参加活动」；理事会三个都授。
--         判定在 AdminRewardPunishController.assertScopeAllowed。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('honor:sanction-activity',  '开出「限制参加活动」处罚（组织部；理事会拥有全部限制权限）',       'honor', 2, 55, NOW(), 0),
('honor:sanction-community', '开出「限制发布社区」处罚（宣传部；理事会拥有全部限制权限）',       'honor', 2, 56, NOW(), 0);

UPDATE permission SET name = '开出「拒绝使用本程序」处罚（秘书部；理事会拥有全部限制权限）', update_time = NOW()
WHERE code = 'honor:sanction-all';

UPDATE permission SET name = '投诉建议全部门（看全部工单并可代任何部门处理）', update_time = NOW()
WHERE code = 'data:complaint-all';
