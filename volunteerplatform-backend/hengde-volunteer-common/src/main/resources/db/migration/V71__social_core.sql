-- 社区核心（V4 社区核心批）：帖子 / 点赞 / 评论 / 关注 / 主页设置 / 不让TA看 + 官方帖权限点。
--
-- 【需求出处】xlsx Row 23（C 列：最热 / 关注 / 官方 / 发布 / 搜索 / 帖子 / 帖子数据 / TA的主页 / 自己主页 / 帖子设置；
--   D 列「登录验证手机号才能看帖子」「未实名的不能发帖、评论、点赞」）；V4规划 D1（四层门）、D6（最热按小时分桶）。
--   审核 / 关键词 / 举报 / 禁言入口 / 置顶 / 互动通知聚合属社区治理批，私信属私信批。
--
-- 【先发后审】review_status 本批只落「待审核」、不据此隐藏（D7：先发后审；关键词「先藏后审」在治理批）。
--
-- 【计数】帖子上的 view / like / comment / share 四个计数是 Row 23「帖子数据」要展示的累计值，落库；
--   点赞 / 关注靠唯一键防重、计数列 +1 / -1（不在 Java 里先查后插）。主页的「发帖量 / 粉丝量 / 关注量」不存列、按行现算。
--
-- 【点赞 / 关注 / 拉黑三张表是物理删除】取消点赞就是没有这一行；留软删行只会让唯一键挡住「再点一次」。
--
-- 【本文件形态】全部是新建表 + 一条权限点 INSERT，新库上没有失败的可能。

CREATE TABLE social_post (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    author_type         TINYINT       NOT NULL COMMENT '1 志愿者 / 2 官方（后台账号）',
    author_id           BIGINT        NOT NULL COMMENT 'volunteer.id 或 admin_user.id（按 author_type）',
    official_department VARCHAR(64)   DEFAULT NULL COMMENT '官方帖：发帖账号的部门快照（删帖权限按它判）',
    official_label      VARCHAR(128)  DEFAULT NULL COMMENT '官方帖：前端显示的「报名或者分队名称」',
    content             VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '正文',
    media_type          TINYINT       NOT NULL DEFAULT 0 COMMENT '0 纯文字 / 1 图片 / 2 视频',
    media_urls          VARCHAR(4000) DEFAULT NULL COMMENT '图片（最多 9 张）或视频（1 个）的 URL，JSON 数组',
    visibility          TINYINT       NOT NULL DEFAULT 0 COMMENT '0 不限制 / 1 隐藏（仅自己）/ 2 我关注的人可看 / 3 关注我的人可看',
    allow_comment       TINYINT       NOT NULL DEFAULT 1 COMMENT '1 允许评论 / 0 禁止评论',
    allow_like          TINYINT       NOT NULL DEFAULT 1 COMMENT '1 允许点赞 / 0 禁止点赞',
    review_status       TINYINT       NOT NULL DEFAULT 0 COMMENT '0 待审核 / 1 通过 / 2 驳回（先发后审，治理批使用）',
    view_count          INT           NOT NULL DEFAULT 0 COMMENT '查看量',
    like_count          INT           NOT NULL DEFAULT 0 COMMENT '点赞量',
    comment_count       INT           NOT NULL DEFAULT 0 COMMENT '评论量（未删除的评论）',
    share_count         INT           NOT NULL DEFAULT 0 COMMENT '分享量',
    edit_time           DATETIME      DEFAULT NULL COMMENT '最后修改时间（改过才有）',
    deleted_by_type     TINYINT       DEFAULT NULL COMMENT '删除人类型 1 志愿者 / 2 后台账号',
    deleted_by          BIGINT        DEFAULT NULL COMMENT '删除人',
    create_time         DATETIME      NOT NULL COMMENT '发布时间',
    update_time         DATETIME      DEFAULT NULL COMMENT '更新时间',
    is_deleted          TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_deleted_time (is_deleted, create_time, id),
    KEY idx_author (author_type, author_id, is_deleted, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '社区帖子（Row 23）';

CREATE TABLE social_post_like (
    id           BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    post_id      BIGINT   NOT NULL COMMENT '帖子',
    volunteer_id BIGINT   NOT NULL COMMENT '点赞的人',
    create_time  DATETIME NOT NULL COMMENT '点赞时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_post_volunteer (post_id, volunteer_id),
    KEY idx_volunteer (volunteer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '帖子点赞（取消即物理删除）';

CREATE TABLE social_comment (
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    post_id              BIGINT       NOT NULL COMMENT '帖子',
    author_type          TINYINT      NOT NULL COMMENT '1 志愿者 / 2 官方（后台账号）',
    author_id            BIGINT       NOT NULL COMMENT 'volunteer.id 或 admin_user.id',
    author_department    VARCHAR(64)  DEFAULT NULL COMMENT '官方评论：部门快照',
    parent_id            BIGINT       DEFAULT NULL COMMENT '回复的是哪条评论（为空＝直接评论帖子）',
    reply_to_author_type TINYINT      DEFAULT NULL COMMENT '被回复评论的作者类型',
    reply_to_author_id   BIGINT       DEFAULT NULL COMMENT '被回复评论的作者',
    content              VARCHAR(500) NOT NULL COMMENT '内容',
    deleted_by_type      TINYINT      DEFAULT NULL COMMENT '删除人类型 1 志愿者 / 2 后台账号',
    deleted_by           BIGINT       DEFAULT NULL COMMENT '删除人',
    create_time          DATETIME     NOT NULL COMMENT '评论时间',
    update_time          DATETIME     DEFAULT NULL COMMENT '更新时间',
    is_deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_post (post_id, is_deleted, id),
    KEY idx_author (author_type, author_id, is_deleted, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '帖子评论';

CREATE TABLE social_follow (
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    follower_id BIGINT   NOT NULL COMMENT '关注的人',
    followee_id BIGINT   NOT NULL COMMENT '被关注的人',
    create_time DATETIME NOT NULL COMMENT '关注时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_follower_followee (follower_id, followee_id),
    KEY idx_followee (followee_id, follower_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '关注关系（取消即物理删除）';

CREATE TABLE social_user_setting (
    volunteer_id   BIGINT       NOT NULL COMMENT '志愿者（一人一行，没设置过就没有行＝全部默认）',
    forbid_follow  TINYINT      NOT NULL DEFAULT 0 COMMENT '1 禁止别人关注我',
    forbid_comment TINYINT      NOT NULL DEFAULT 0 COMMENT '1 我的帖子一律禁止评论',
    forbid_like    TINYINT      NOT NULL DEFAULT 0 COMMENT '1 我的帖子一律禁止点赞',
    bio            VARCHAR(200) DEFAULT NULL COMMENT '主页备注',
    update_time    DATETIME     DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (volunteer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '社区主页设置（Row 23「首页可以设置」）';

CREATE TABLE social_block (
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    owner_id    BIGINT   NOT NULL COMMENT '设置的人',
    target_id   BIGINT   NOT NULL COMMENT '不让看的人',
    create_time DATETIME NOT NULL COMMENT '设置时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_owner_target (owner_id, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '不让TA看（取消即物理删除）';

INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('social:official', '官方帖（本部门发布 / 删除 / 回复评论）', 'social', 2, 80, NOW(), 0),
('social:official-all', '官方帖全部门（删除其他部门的官方帖与评论，默认给宣传部）', 'social', 2, 81, NOW(), 0);
