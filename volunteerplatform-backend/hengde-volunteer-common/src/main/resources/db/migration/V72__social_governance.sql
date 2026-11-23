-- 社区治理（V4 社区治理批）：多级审核 / 关键词风控 / 举报 / 禁言 / 互动通知 + 6 个权限点。
--
-- 【需求出处】xlsx Row 23 F「管理、禁言、隐藏、删除、发布、置顶；需要两个界面，按照时间排序显示帖子、评论；最高权限管理员才可以看到发布人的真实姓名和学校；
--   后台可以给予某个用户限制，如：禁止发帖几天、禁止评论几天、禁止点赞几天」「审核功能：用户发布帖子后正常显示，但后台显示为待审核阶段，
--   可由后台设置该帖子审核几次，设置审核员，有违规则封号等措施」「关键词风控：……触发关键词，则自动进入后台插队审核」；
--   Row 23 C「互动：展示他人评论（回复）自己帖子的评论和时间，点击可以跳转，长按可以删除、举报」；Row 23 D「点赞、评论等通知每隔20分钟订阅消息汇总提示一次」；
--   Row 59 后台首页待办「待审核帖子 / 举报待审核」。私聊的审核与关键词属私信批。
--
-- 【帖子上的新列】在 V73（一条 ALTER，与本文件分开：本文件全是新建表，新库上没有失败的可能）。
-- 【禁言】落在 auth 的 volunteer_sanction（能力域 2/4/5/6，来源类型 2＝社区禁言，source_id＝本文件的 social_ban.id），
--   不另建「能不能发帖」的第二套事实来源（V4规划承重条款 1）。
-- 【举报去重】生成列唯一键：同一个人对同一个对象只能有一条「待处理」的举报；处理完释放，可再举报。
-- 【互动去重】点赞与关注按「谁对谁的哪条帖子」去重：同一个人反复点赞取消，对方只收到一条。

CREATE TABLE social_review_setting (
    id           BIGINT   NOT NULL COMMENT '恒为 1（单行配置）',
    review_levels TINYINT NOT NULL DEFAULT 1 COMMENT '帖子要审核几级（1~3，Q3）',
    updated_by   BIGINT   DEFAULT NULL COMMENT '最后修改人 admin_user.id',
    update_time  DATETIME DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '社区审核设置（单行）';

INSERT INTO social_review_setting (id, review_levels, update_time) VALUES (1, 1, NOW());

CREATE TABLE social_reviewer (
    id            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    admin_user_id BIGINT   NOT NULL COMMENT '审核员（后台账号）',
    level         TINYINT  NOT NULL COMMENT '负责第几级（1~3）',
    created_by    BIGINT   DEFAULT NULL COMMENT '设置人',
    create_time   DATETIME NOT NULL COMMENT '设置时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_level (admin_user_id, level)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '社区审核员（Row 23 F「设置审核员」）';

CREATE TABLE social_review_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    post_id       BIGINT       NOT NULL COMMENT '帖子',
    level         TINYINT      NOT NULL COMMENT '第几级',
    admin_user_id BIGINT       NOT NULL COMMENT '审核人',
    action        TINYINT      NOT NULL COMMENT '1 通过 / 2 驳回',
    reason        VARCHAR(255) DEFAULT NULL COMMENT '驳回原因',
    create_time   DATETIME     NOT NULL COMMENT '审核时间',
    PRIMARY KEY (id),
    KEY idx_post (post_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '社区帖子审核记录（只追加）';

CREATE TABLE social_keyword (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    word        VARCHAR(50) NOT NULL COMMENT '关键词（不区分大小写地包含即命中）',
    created_by  BIGINT      DEFAULT NULL COMMENT '添加人',
    create_time DATETIME    NOT NULL COMMENT '添加时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_word (word)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '社区风控关键词（Row 23 F）';

CREATE TABLE social_report (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    reporter_id  BIGINT       NOT NULL COMMENT '举报人 volunteer.id',
    target_type  TINYINT      NOT NULL COMMENT '1 帖子 / 2 评论',
    target_id    BIGINT       NOT NULL COMMENT '被举报的帖子或评论',
    post_id      BIGINT       NOT NULL COMMENT '所在帖子（举报帖子时＝target_id）',
    reason       VARCHAR(200) NOT NULL COMMENT '举报理由',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '0 待处理 / 1 成立 / 2 不成立',
    handle_action TINYINT     DEFAULT NULL COMMENT '成立时的处置 0 不处置 / 1 隐藏 / 2 删除',
    handle_note  VARCHAR(255) DEFAULT NULL COMMENT '处理说明',
    handled_by   BIGINT       DEFAULT NULL COMMENT '处理人 admin_user.id',
    handled_time DATETIME     DEFAULT NULL COMMENT '处理时间',
    create_time  DATETIME     NOT NULL COMMENT '举报时间',
    active_key   VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN status = 0 THEN CONCAT(reporter_id, ':', target_type, ':', target_id) ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_report (active_key),
    KEY idx_status_time (status, create_time),
    KEY idx_target (target_type, target_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '社区举报（帖子 / 评论）';

CREATE TABLE social_ban (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（＝volunteer_sanction.source_id，来源类型 2）',
    volunteer_id BIGINT      NOT NULL COMMENT '被禁言的志愿者',
    scope       TINYINT      NOT NULL COMMENT '能力域 2 全部社区写入 / 4 禁止发帖 / 5 禁止评论 / 6 禁止点赞',
    days        INT          NOT NULL COMMENT '天数',
    reason      VARCHAR(255) NOT NULL COMMENT '原因',
    created_by  BIGINT       NOT NULL COMMENT '开出人 admin_user.id',
    create_time DATETIME     NOT NULL COMMENT '开出时间',
    lifted_by   BIGINT       DEFAULT NULL COMMENT '提前解除人',
    lifted_time DATETIME     DEFAULT NULL COMMENT '提前解除时间',
    lift_reason VARCHAR(255) DEFAULT NULL COMMENT '解除原因',
    PRIMARY KEY (id),
    KEY idx_volunteer (volunteer_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '社区禁言记录（生效与否以 volunteer_sanction 为准）';

CREATE TABLE social_interaction (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    recipient_id BIGINT      NOT NULL COMMENT '收到互动的人',
    actor_id     BIGINT      NOT NULL COMMENT '做出互动的人',
    type         TINYINT     NOT NULL COMMENT '1 赞了你的帖子 / 2 评论了你的帖子 / 3 回复了你的评论 / 4 关注了你',
    post_id      BIGINT      DEFAULT NULL COMMENT '相关帖子',
    comment_id   BIGINT      DEFAULT NULL COMMENT '相关评论',
    is_read      TINYINT     NOT NULL DEFAULT 0 COMMENT '0 未读 / 1 已读',
    create_time  DATETIME    NOT NULL COMMENT '发生时间',
    is_deleted   TINYINT     NOT NULL DEFAULT 0 COMMENT '本人长按删除',
    dedupe_key   VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN type IN (1, 4) THEN CONCAT(recipient_id, ':', type, ':', actor_id, ':', IFNULL(post_id, 0)) ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dedupe (dedupe_key),
    KEY idx_recipient (recipient_id, is_deleted, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '社区互动（Row 23「互动」）';

CREATE TABLE social_interaction_digest (
    volunteer_id   BIGINT   NOT NULL COMMENT '收到互动的人',
    last_digest_id BIGINT   NOT NULL DEFAULT 0 COMMENT '已汇总提示到哪条互动（id）',
    update_time    DATETIME DEFAULT NULL COMMENT '最后一次汇总时间',
    PRIMARY KEY (volunteer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '互动汇总提示的水位（每 20 分钟一次，Row 23 D）';

INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('social:post-manage', '社区帖子与评论管理（列表 / 隐藏 / 删除 / 置顶）', 'social', 2, 82, NOW(), 0),
('social:review', '社区帖子审核（还须被设为某一级审核员）', 'social', 2, 83, NOW(), 0),
('social:review-setting', '社区审核设置（审核级数 / 审核员 / 风控关键词）', 'social', 2, 84, NOW(), 0),
('social:report', '社区举报处理', 'social', 2, 85, NOW(), 0),
('social:ban', '社区禁言（禁止发帖 / 评论 / 点赞几天）', 'social', 2, 86, NOW(), 0),
('social:real-name', '查看社区发布人的真实姓名与学校（Row 23 F 最高权限，默认不授任何人）', 'social', 2, 87, NOW(), 0);
