-- 社区私信（V4 私信批）：xlsx Row 23「私信：完全参考抖音的私信界面」「私聊：用户双方可以通过主页搭建起聊天通道」
-- + F 列「聊天记录：所有聊天记录均保存在系统后台、查看权限仅为最高管理员，亦可由管理员下放该功能至某个账号使用」
-- +「用户投诉私聊信息时，审核员能看到聊天内容，并进行审核」+「关键词风控：……私聊如触发关键词，则自动进入后台插队审核」。
--
-- 【会话按「两人」规范化】small_id / large_id 是两人 id 排序后的结果，唯一键保证一对人只有一条会话——
--   不规范化就会出现 (A,B) 与 (B,A) 两条，未读数与最后一条消息各记一半。
-- 【消息先落库再推送】（V4规划 D8）：推送失败只是「晚看到」，落库失败才是丢消息。
-- 【清空聊天记录只对自己】cleared_id 是水位，后台保存的记录一条都不少（Row 23 F 的硬要求）。
-- 【未读数记在会话上】列表页要按会话显示未读数，逐会话 COUNT 消息表在消息多了之后就是全表扫。
-- 【关键词命中不藏消息】帖子是先藏后审，私聊藏起来等于单方面切断对话且双方都不知道；命中的消息照常送达，
--   另生成一条来源为「关键词」的工单插队进审核队列——「自动进入后台插队审核」说的是工单，不是拦截。

CREATE TABLE social_conversation (
    id               BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    small_id         BIGINT   NOT NULL COMMENT '两人中 id 较小的一方',
    large_id         BIGINT   NOT NULL COMMENT '两人中 id 较大的一方',
    last_message_id  BIGINT   DEFAULT NULL COMMENT '最后一条消息',
    last_content     VARCHAR(200) DEFAULT NULL COMMENT '最后一条消息的摘要（会话列表直接显示）',
    last_sender_id   BIGINT   DEFAULT NULL COMMENT '最后一条消息的发送人',
    last_time        DATETIME DEFAULT NULL COMMENT '最后一条消息的时间',
    small_unread     INT      NOT NULL DEFAULT 0 COMMENT '较小一方的未读数',
    large_unread     INT      NOT NULL DEFAULT 0 COMMENT '较大一方的未读数',
    small_cleared_id BIGINT   NOT NULL DEFAULT 0 COMMENT '较小一方「清空聊天记录」的水位（只对他自己隐藏）',
    large_cleared_id BIGINT   NOT NULL DEFAULT 0 COMMENT '较大一方的清空水位',
    small_sent       INT      NOT NULL DEFAULT 0 COMMENT '较小一方发过多少条（「对方没回之前最多 3 条」靠它）',
    large_sent       INT      NOT NULL DEFAULT 0 COMMENT '较大一方发过多少条',
    create_time      DATETIME NOT NULL COMMENT '建立时间',
    update_time      DATETIME DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pair (small_id, large_id),
    KEY idx_small (small_id, last_time),
    KEY idx_large (large_id, last_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '私信会话（一对人一条）';

CREATE TABLE social_message (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id BIGINT        NOT NULL COMMENT '所属会话',
    sender_id       BIGINT        NOT NULL COMMENT '发送人 volunteer.id',
    receiver_id     BIGINT        NOT NULL COMMENT '接收人 volunteer.id',
    content         VARCHAR(1000) DEFAULT NULL COMMENT '文字内容',
    image_url       VARCHAR(512)  DEFAULT NULL COMMENT '图片（只收本系统传到 social/ 下的）',
    keyword_hit     TINYINT       NOT NULL DEFAULT 0 COMMENT '1 命中关键词',
    keyword_hits    VARCHAR(255)  DEFAULT NULL COMMENT '命中了哪些词（给审核员看）',
    create_time     DATETIME      NOT NULL COMMENT '发送时间',
    update_time     DATETIME      DEFAULT NULL COMMENT '更新时间',
    is_deleted      TINYINT       NOT NULL DEFAULT 0 COMMENT '后台删除违规内容（双方都看不到；后台仍查得到）',
    PRIMARY KEY (id),
    KEY idx_conversation (conversation_id, id),
    KEY idx_sender (sender_id, id),
    KEY idx_keyword (keyword_hit, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '私信消息（后台永久保存）';

CREATE TABLE social_chat_report (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    source          TINYINT      NOT NULL DEFAULT 1 COMMENT '1 用户投诉 / 2 关键词命中自动生成（插队在前）',
    reporter_id     BIGINT       DEFAULT NULL COMMENT '投诉人（关键词工单为空）',
    target_id       BIGINT       NOT NULL COMMENT '被投诉 / 命中关键词的那个人',
    conversation_id BIGINT       NOT NULL COMMENT '会话',
    message_id      BIGINT       DEFAULT NULL COMMENT '关键词命中的那条消息',
    reason          VARCHAR(200) DEFAULT NULL COMMENT '投诉理由 / 命中的词',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0 待处理 / 1 成立 / 2 不成立',
    handle_note     VARCHAR(255) DEFAULT NULL COMMENT '处理说明',
    handled_by      BIGINT       DEFAULT NULL COMMENT '处理人 admin_user.id',
    handled_time    DATETIME     DEFAULT NULL COMMENT '处理时间',
    create_time     DATETIME     NOT NULL COMMENT '创建时间',
    active_key      VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN status = 0 THEN CONCAT(COALESCE(reporter_id, 0), ':', conversation_id, ':', COALESCE(message_id, 0)) ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_chat_report (active_key),
    KEY idx_status (status, source DESC, id),
    KEY idx_conversation (conversation_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '私聊投诉与关键词工单（Row 23 F）';

ALTER TABLE social_user_setting
    ADD COLUMN forbid_chat TINYINT NOT NULL DEFAULT 0 COMMENT '1 禁止别人给我发私信' AFTER forbid_like;

INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('social:chat-view', '聊天记录查看与私聊投诉处理（默认不授任何人，由超管显式下放）', 'social', 2, 105, NOW(), 0);
