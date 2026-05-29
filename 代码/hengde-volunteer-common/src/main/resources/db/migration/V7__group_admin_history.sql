-- V7 organization: 小组解散/审批字段解耦、成员审批人、组长变更历史。
-- 与 V5 解耦：V5 的 reject_reason 同时承担「建组被拒原因」和「解散原因」，本版起新增 dissolve_*
-- 三件套独立存储；reject_reason 保留兼容老数据，仅用于「拒绝建组」。

ALTER TABLE volunteer_group
    ADD COLUMN dissolve_time   DATETIME     NULL COMMENT '解散时间',
    ADD COLUMN dissolve_reason VARCHAR(255) NULL COMMENT '解散原因（与 reject_reason 解耦）',
    ADD COLUMN dissolve_by     BIGINT       NULL COMMENT '解散操作管理员 admin_user.id',
    ADD COLUMN approved_time   DATETIME     NULL COMMENT '建组审批通过时间（≠ create_time，后者是申请时间）',
    ADD COLUMN approved_by     BIGINT       NULL COMMENT '审批人 admin_user.id';

ALTER TABLE volunteer_group_member
    ADD COLUMN audit_by BIGINT NULL COMMENT '审批人 volunteer.id（组长或管理员）';

-- 组长变更历史：每次 transferLeader（含建组首次任命）都追加一条
-- 字段约定与 BaseEntity 对齐：create_time/update_time/is_deleted 三件套都要有
CREATE TABLE volunteer_group_leader_history (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    group_id      BIGINT       NOT NULL COMMENT '小组 id',
    old_leader_id BIGINT       NULL     COMMENT '前任组长 volunteer.id（建组首次任命为 NULL）',
    new_leader_id BIGINT       NOT NULL COMMENT '新组长 volunteer.id',
    change_time   DATETIME     NOT NULL COMMENT '变更时间',
    operator_type TINYINT      NOT NULL COMMENT '1=组长主动转移 / 2=后台管理员转移 / 3=建组审批首次任命',
    operator_id   BIGINT       NOT NULL COMMENT '操作人 id：志愿者 id 或 admin_user.id',
    reason        VARCHAR(255) NULL     COMMENT '变更原因',
    create_time   DATETIME     DEFAULT NULL,
    update_time   DATETIME     DEFAULT NULL,
    is_deleted    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_group_time (group_id, change_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '组长变更历史';
