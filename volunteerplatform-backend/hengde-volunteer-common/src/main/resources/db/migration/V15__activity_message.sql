-- V15 activity 域：活动留言（V1.1 第 3 批·PR1）
-- 需求：志愿者在活动下发表留言，活动留言列表对所有已登录用户可见；
--   管理端（activity:manage）可下架（逻辑删除）某条留言。
-- status 预留将来审核（0隐藏/1正常）；当前发表即 1 正常可见。

CREATE TABLE activity_message (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    activity_id  BIGINT       NOT NULL COMMENT '活动 activity.id',
    volunteer_id BIGINT       NOT NULL COMMENT '发表人 volunteer.id',
    content      VARCHAR(500) NOT NULL COMMENT '留言内容',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '0隐藏/1正常（预留审核）',
    create_time  DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_time  DATETIME     DEFAULT NULL COMMENT '更新时间',
    is_deleted   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删（管理端下架）',
    PRIMARY KEY (id),
    KEY idx_activity (activity_id, status, is_deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '活动留言';
