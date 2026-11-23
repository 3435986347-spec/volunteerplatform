-- V8 activity 补字段：发布团队/联系人/分角色报名开放时间
-- 需求方 V1 复盘指出，活动详情内部展示需要：
--   联系人姓名+电话、发布团队名称、管理团队/临时负责人/志愿者各自的报名开放时间。
-- 这些都是发布表单层面的元数据，不影响已有报名/活动状态语义，纯追加列。
-- 三个 open_* 时间字段语义：null=该角色不受开放时间约束（V1 默认即时可报）；
-- 业务校验仅 EnrollmentService 用 enroll_open_volunteer 拦截志愿者端 enroll/proxy。
-- 临时负责人角色 V1 未落地（CLAUDE.md），open_leader 字段先建、逻辑后做。

ALTER TABLE activity
    ADD COLUMN contact_name          VARCHAR(32) DEFAULT NULL COMMENT '联系人姓名',
    ADD COLUMN contact_phone         VARCHAR(32) DEFAULT NULL COMMENT '联系人电话',
    ADD COLUMN publisher_dept_name   VARCHAR(64) DEFAULT NULL COMMENT '发布团队/部门名称',
    ADD COLUMN enroll_open_manager   DATETIME    DEFAULT NULL COMMENT '管理团队报名开放时间（null=即时可报）',
    ADD COLUMN enroll_open_leader    DATETIME    DEFAULT NULL COMMENT '临时负责人报名开放时间（V1 未落地角色，字段先建）',
    ADD COLUMN enroll_open_volunteer DATETIME    DEFAULT NULL COMMENT '志愿者报名开放时间（EnrollmentService.enroll/proxy 据此拦截）';
