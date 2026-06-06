-- V19 活动发布审核：小程序（志愿者端）提交的活动需后台审核才上线；后台直接发布的不审核。
-- 需求：管理团队志愿者从小程序 POST /v/activity/activities 提交的活动落「待审核发布」(status=4)，
--      不对志愿者端可见；部长在后台审核——通过→上线(status=已发布)，驳回→status=5 并记原因。
--      后台 /a 发布（publish/recurring/historical/copy）仍直接上线，不进审核队列。
-- 复用 activity.status：扩 4=待审核发布 / 5=发布被驳回（原 0草稿/1已发布/2已结束/3已取消）。
-- 权限点：activity:publish-audit（活动发布审核，部长；不开放给志愿者，volunteer_grantable 默认 0）。

ALTER TABLE activity
    ADD COLUMN publish_reject_reason VARCHAR(512) DEFAULT NULL COMMENT '发布审核驳回原因（status=5 时）',
    ADD COLUMN publish_review_by     BIGINT       DEFAULT NULL COMMENT '发布审核人 admin_user.id',
    ADD COLUMN publish_review_time   DATETIME     DEFAULT NULL COMMENT '发布审核时间';

-- 权限点：接 activity:backfill-audit sort=37 之后取 38。审核类沿用 type=3；不开放给志愿者（volunteer_grantable 默认 0）。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('activity:publish-audit', '活动发布审核', 'activity', 3, 38, NOW(), 0);
