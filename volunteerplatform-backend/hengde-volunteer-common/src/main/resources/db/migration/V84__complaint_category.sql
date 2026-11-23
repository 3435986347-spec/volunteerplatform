-- V84 投诉建议加「类别」，按类别进部门。
--
-- 【依据】协会 2026-09-02 答复：投诉建议 活动类 → 组织部 / 协会内部类 → 秘书部 / 社区类 → 宣传部；
--         协会 2026-09-16 答复：监察部与分队管理部一年前已撤销。
--         需求表 Row 43 原写「投诉默认到监察部」，V65 照此把默认部门配成监察部——那是一个已经没有人的部门，
--         新工单只有持 data:complaint-all 的账号看得到。改为提交时由志愿者选类别，按类别进部门。
--
-- 【取值】1活动类 / 2协会内部类 / 3社区类。部门名不入库表，走配置 hengde.data.complaint.routes.*，
--         与 admin_user.department 那段文字一字不差（部门没有主数据表，沿用 V65 的口径）。
--
-- 【存量】V84 之前的工单为 NULL，不回填：当时没有问过类别，按内容猜会猜错；它们停在哪个部门就由哪个部门继续处理，
--         持 data:complaint-all 的账号可以把停在监察部的工单流转出去。
ALTER TABLE data_complaint
    ADD COLUMN category TINYINT DEFAULT NULL COMMENT '1活动类/2协会内部类/3社区类（决定进哪个部门；V84 之前的工单为 NULL）' AFTER complaint_type;
