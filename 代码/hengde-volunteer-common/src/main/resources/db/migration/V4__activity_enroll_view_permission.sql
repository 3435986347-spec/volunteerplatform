-- V4 activity 域：新增「查看报名名单」权限点 activity:enroll-view
-- 背景：报名列表带出志愿者明文手机号（PII），原先仅由菜单级 activity:menu 门控，比同样出手机号的
--   activity:enroll-export（功能级）门槛更低。拆出独立功能级权限单独门控，使 PII 暴露面与导出对齐。
-- type=2（功能级，与 enroll-add/export/delete 同档），sort=28 接在 enroll-audit(27) 之后。
-- 超管走 * 通配不受影响；其他角色需由后台重新分配此权限后方可查看报名名单。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('activity:enroll-view', '查看报名名单', 'activity', 2, 28, NOW(), 0);
