-- V90 权限点「恢复已注销的账号」。
--
-- 【依据】协会 2026-09-16：账号恢复 → 理事会；本人希望重新加入时后台可恢复账号，恢复时换回真实号码。
--         与「停用 / 恢复」（user:status，管的是被停用的账号）不是一回事：注销是本人退出协会，恢复要重新绑一个手机号。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('user:restore', '恢复已注销的账号（理事会）', 'user', 2, 16, NOW(), 0);
