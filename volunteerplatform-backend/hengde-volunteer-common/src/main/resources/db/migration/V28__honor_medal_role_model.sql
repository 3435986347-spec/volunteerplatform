-- 勋章与榜样（V2 第 3 批）。
--
-- 需求原文（xlsx）：「勋章样式、上传、审核、发放，均由后台审核」——勋章的全生命周期都在后台完成，
--   志愿者端只负责展示。已明确为**双重审核**：①样式上传后须审核通过才可用于发放；
--   ②每次发放也须审核通过才对志愿者生效。两处审核都沿用项目既有的 CAS 条件更新模式
--   （与 V14 考勤变更审核、V19 活动发布审核一致）。

-- ── 勋章定义（样式）──
CREATE TABLE honor_medal (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name          VARCHAR(64)  NOT NULL COMMENT '勋章名称',
    icon_url      VARCHAR(512) NOT NULL COMMENT '勋章图标 URL（经 /a/files/upload?dir=medal 上传）',
    description   VARCHAR(512) DEFAULT NULL COMMENT '勋章说明，志愿者端展示',
    -- 获取条件**本批只存不判**：自动发放引擎未做，此处是协会规则的登记位。
    -- 但「获取进度」可以现在就展示——1/2/3 三种阈值的当前值在既有服务里都能直接读到
    -- （时长/次数取 ServiceRecordService，积分取 PointService 的「累计获得」），
    -- 展示进度与自动发放是两件事，前者不需要引擎。
    condition_type      TINYINT NOT NULL DEFAULT 0 COMMENT '获取条件 0手动授予/1累计服务时长(分钟)/2累计活动次数/3累计获得积分',
    condition_threshold BIGINT  DEFAULT NULL COMMENT '条件阈值；condition_type=0 时为 NULL',
    -- 勋章可附带积分奖励。PointSourceType.MEDAL(3) 早在 V24 就为此预留了来源码，此处启用。
    -- 默认 0=不发积分，协会不需要这个玩法时无需关心。
    reward_points INT          NOT NULL DEFAULT 0 COMMENT '附带积分奖励，0=不发',
    sort          INT          NOT NULL DEFAULT 0 COMMENT '展示排序，小的在前',
    -- 状态机：0草稿 →(提交) 1待审核 →(通过) 2已启用 / (驳回) 3已驳回 →(改后重交) 1待审核
    --         2已启用 →(停用) 4已停用（不可再发放，已生效的发放记录不受影响）
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态 0草稿/1待审核/2已启用/3已驳回/4已停用',
    reject_reason VARCHAR(512) DEFAULT NULL COMMENT '样式审核驳回原因',
    review_by     BIGINT       DEFAULT NULL COMMENT '样式审核人 admin_user.id',
    review_time   DATETIME     DEFAULT NULL COMMENT '样式审核时间',
    create_time   DATETIME     DEFAULT NULL,
    update_time   DATETIME     DEFAULT NULL,
    is_deleted    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- 志愿者端列表的唯一形态：已启用的按 sort 正序
    KEY idx_status_sort (status, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '勋章定义';

-- ── 勋章发放记录 ──
CREATE TABLE honor_medal_grant (
    id            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    medal_id      BIGINT   NOT NULL COMMENT '勋章 honor_medal.id',
    volunteer_id  BIGINT   NOT NULL COMMENT '志愿者 volunteer.id',
    -- 自动(2)预留取值：自动判定引擎本批未做，当前只会产生手动(1)。
    -- 与 honor_ranking_snapshot.rank_type=4 同理——占位而不放行，免得把「未开放」伪装成「没数据」。
    grant_type    TINYINT  NOT NULL DEFAULT 1 COMMENT '发放方式 1手动/2自动（自动引擎未做，预留）',
    -- 发放时**快照**勋章当时的积分奖励值，审核通过时按快照发分。
    -- 不在审核时现读定义：申请与审核之间管理员若改了勋章的 reward_points，
    -- 审核人批准的就不再是他看到的那个数了。快照同时留下审计痕迹。
    reward_points INT      NOT NULL DEFAULT 0 COMMENT '发起时快照的积分奖励值',
    reason        VARCHAR(512) DEFAULT NULL COMMENT '授予理由',
    status        TINYINT  NOT NULL DEFAULT 0 COMMENT '状态 0待审核/1已生效/2已驳回',
    reject_reason VARCHAR(512) DEFAULT NULL COMMENT '发放审核驳回原因',
    apply_by      BIGINT   DEFAULT NULL COMMENT '发起人 admin_user.id',
    apply_time    DATETIME DEFAULT NULL COMMENT '发起时间',
    review_by     BIGINT   DEFAULT NULL COMMENT '发放审核人 admin_user.id',
    review_time   DATETIME DEFAULT NULL COMMENT '发放审核时间',
    -- 「同一勋章不重复授予同一人」的约束载体，仿 V9 的 uk_active_volunteer：
    --   待审(0)或已生效(1)且未删时取 '勋章-志愿者'，否则 NULL。MySQL 唯一索引允许多个 NULL，
    --   故**被驳回(2)的历史行可以共存**——驳回之后应当允许重新发起，用死约束会把人永久挡在门外。
    -- VIRTUAL 列不落盘，按行即时计算。
    active_grant_lock VARCHAR(64)
        GENERATED ALWAYS AS (
            CASE WHEN status IN (0, 1) AND is_deleted = 0
                THEN CONCAT(medal_id, '-', volunteer_id) END
        ) VIRTUAL COMMENT '防重复授予唯一约束载体：待审/已生效且未删时=medal-volunteer，否则NULL',
    create_time   DATETIME DEFAULT NULL,
    update_time   DATETIME DEFAULT NULL,
    is_deleted    TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_grant (active_grant_lock),
    -- 「我的勋章」：按人取已生效的
    KEY idx_volunteer_status (volunteer_id, status),
    -- 后台待审列表
    KEY idx_status_apply_time (status, apply_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '勋章发放记录';

-- ── 榜样 ──
-- 数据形态与 publicity_banner 高度一致（标题/图片/跳转/排序/上下架），刻意保持同构，
-- 后台可复用同一套列表-编辑-上下架交互，不另起一套心智模型。
CREATE TABLE honor_role_model (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    title       VARCHAR(128) NOT NULL COMMENT '标题',
    subtitle    VARCHAR(256) DEFAULT NULL COMMENT '副标题',
    image_url   VARCHAR(512) DEFAULT NULL COMMENT '图片 URL',
    link_url    VARCHAR(512) DEFAULT NULL COMMENT '跳转链接（推文等）',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '展示排序，小的在前',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态 0下架/1上架',
    create_time DATETIME     DEFAULT NULL,
    update_time DATETIME     DEFAULT NULL,
    is_deleted  TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_status_sort (status, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '榜样';

-- 权限点。样式管理 / 样式审核 / 发起发放 / 发放审核分开：双重审核的意义就在于
-- 「录入的人」与「批准的人」不是同一个；合成一个点等于把审核降级成走过场。
-- type=2「操作」，type=3「审核」（与既有 seed 的口径一致）。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('honor:medal', '勋章定义管理', 'honor', 2, 43, NOW(), 0),
('honor:medal-audit', '勋章样式审核', 'honor', 3, 44, NOW(), 0),
('honor:medal-grant', '勋章发放发起', 'honor', 2, 45, NOW(), 0),
('honor:medal-grant-audit', '勋章发放审核', 'honor', 3, 46, NOW(), 0),
('honor:role-model', '榜样管理', 'honor', 2, 47, NOW(), 0);
