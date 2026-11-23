-- V14 activity 域：考勤/积分变更二次审核（V1.1 第 2 批·PR2）
-- 需求：组织部修改志愿者的签到时间/签退时间/积分 → 不立即生效，建一条待审记录，
--   部长(activity:attendance-audit)二次审核通过后才应用到 activity_attendance；拒绝则不应用。
-- 权限点 activity:attendance-edit（申请）/activity:attendance-audit（审核）已在 V10 种子，无需再种。
-- change_type：1=签到时间、2=签退时间、3=积分。改签到/签退在审核通过时一并按 签退−签到 重算 service_minutes
--   （请假/缺席仍为 0；缺一边时长则保持原值）；改积分直接覆盖 points_award。
-- old_value/new_value 统一存字符串（时间存 ISO，积分存整数），按 change_type 解释。

CREATE TABLE activity_attendance_change (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    attendance_id  BIGINT       NOT NULL COMMENT '考勤记录 activity_attendance.id',
    change_type    TINYINT      NOT NULL COMMENT '变更项 1签到时间/2签退时间/3积分',
    old_value      VARCHAR(64)  DEFAULT NULL COMMENT '原值快照（申请时；时间ISO或整数）',
    new_value      VARCHAR(64)  DEFAULT NULL COMMENT '申请新值（时间ISO或整数）',
    reason         VARCHAR(512) DEFAULT NULL COMMENT '变更理由',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审/1通过(已应用)/2拒绝',
    requested_by   BIGINT       DEFAULT NULL COMMENT '申请人（组织部）admin_user.id',
    requested_time DATETIME     DEFAULT NULL COMMENT '申请时间',
    audited_by     BIGINT       DEFAULT NULL COMMENT '审核人（部长）admin_user.id',
    audited_time   DATETIME     DEFAULT NULL COMMENT '审核时间',
    audit_reason   VARCHAR(512) DEFAULT NULL COMMENT '审核意见/拒绝原因',
    create_time    DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_time    DATETIME     DEFAULT NULL COMMENT '更新时间',
    is_deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    KEY idx_attendance (attendance_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '考勤/积分变更二次审核';
