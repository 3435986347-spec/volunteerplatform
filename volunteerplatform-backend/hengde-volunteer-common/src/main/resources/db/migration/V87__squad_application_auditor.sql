-- V87 分队加入申请记下「谁审的」。
--
-- 【依据】协会 2026-09-02：分队加入申请「分队自己审，也可以由部门审核通过」；需求表 Row 60「由上述分队进行审核是否通过」。
--         此前只有后台（部门）能审，而且连审核人都没记。现在分队负责人（志愿者）也能在小程序里审，
--         两类审核人的 id 来自两张表（admin_user / volunteer），不带类型就分不清是谁审的——与
--         mall_order.pickup_operator_type、奖惩单的初审 / 终审记录同一个理由。
--
-- 【存量】V87 之前审过的申请两列都是 NULL：当时没有记，不回填（猜出来的审核人比空着更糟）。
ALTER TABLE squad_application
    ADD COLUMN audit_by      BIGINT  DEFAULT NULL COMMENT '审核人 id：类型 1 为 admin_user.id，类型 2 为 volunteer.id（分队负责人）' AFTER audit_time,
    ADD COLUMN audit_by_type TINYINT DEFAULT NULL COMMENT '审核人类型 1后台账号（部门审核）/2分队负责人（V87 之前为 NULL）' AFTER audit_by;
