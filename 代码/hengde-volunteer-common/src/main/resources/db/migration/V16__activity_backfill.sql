-- V16 activity 域：历史活动标记 + 活动补录 + 部长审核（V1.1 第 3 批·PR3）
-- 需求：
--  1) 历史活动发布——补录之前未在系统发布过的已发生活动，作为补录载体（status=已结束、志愿者端不可见）。
--     用 activity.is_historical 区分「普通已发布活动」(补录得积分) 与「历史活动」(补录只记时长不得积分)。
--  2) 活动补录——按手机号/身份证精确匹配志愿者 + 指定时间段 → 待部长审核；通过即终态：
--     直接落一条已确认(secretary_status=1)考勤行，普通活动按公式发积分、历史活动只记时长。
-- 权限点：activity:backfill（补录申请，组织部）/ activity:backfill-audit（补录审核，部长）。

ALTER TABLE activity
    ADD COLUMN is_historical TINYINT NOT NULL DEFAULT 0 COMMENT '0普通活动/1历史补录活动（历史活动补录只记时长不发积分）';

CREATE TABLE activity_backfill (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    activity_id     BIGINT       NOT NULL COMMENT '活动 activity.id',
    volunteer_id    BIGINT       NOT NULL COMMENT '补录目标 volunteer.id（按手机号/身份证精确匹配）',
    slot_id         BIGINT       NOT NULL COMMENT '补录时间段 activity_slot.id（据此算时长）',
    service_minutes INT          NOT NULL DEFAULT 0 COMMENT '服务时长快照（按 slot 时长）',
    grant_points    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否发积分：申请时据 activity.is_historical 快照，普通1/历史0',
    matched_by      VARCHAR(32)  DEFAULT NULL COMMENT '匹配方式 idCard/phone（审计展示）',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审/1通过(已生效)/2拒绝',
    reason          VARCHAR(512) DEFAULT NULL COMMENT '补录理由',
    requested_by    BIGINT       DEFAULT NULL COMMENT '申请人（组织部）admin_user.id',
    requested_time  DATETIME     DEFAULT NULL COMMENT '申请时间',
    audited_by      BIGINT       DEFAULT NULL COMMENT '审核人（部长）admin_user.id',
    audited_time    DATETIME     DEFAULT NULL COMMENT '审核时间',
    audit_reason    VARCHAR(512) DEFAULT NULL COMMENT '审核意见/拒绝原因',
    create_time     DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_time     DATETIME     DEFAULT NULL COMMENT '更新时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    KEY idx_activity (activity_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '活动补录申请与审核';

-- 权限点：接 activity 组 sort=34、org:manager-flag sort=35 之后，取 36/37。审核类沿用 type=3。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('activity:backfill',       '活动补录',     'activity', 2, 36, NOW(), 0),
('activity:backfill-audit', '活动补录审核', 'activity', 3, 37, NOW(), 0);
