-- 个人中心补全（V4 个人中心补全批）：地址管理、个人中心内容（我的保险 / 联系客服）、订阅通知偏好。
--
-- 【需求出处】xlsx Row 40「地址新增、删除、修改、置顶」；Row 42「我的保险：文字和图片展示」；
--   Row 48「联系客服：展示二维码及文字电话（后台设置）/ 订阅通知：短信和通知提醒，用户可自主选择关闭或打开某个内容的提醒」。
--
-- 【地址】收件电话密文存储（与商城快递单的收件电话同口径）。「置顶」一人至多一条，由生成列唯一键兜底；
--   置顶动作本身按人加锁串行（先清旧的再置新的），唯一键防的是锁之外的写入路径。
--
-- 【个人中心内容】保险与客服都是「协会在后台设置、所有人看同一份」的一段文字 + 图片（V4规划 Q20 默认；
--   若保险是每人一份保单，要另建按人的表）。content_key 固定取值：insurance / customer-service。
--
-- 【订阅通知偏好】只存「和默认不一样」的那几行（默认全开），一人一话题一行；话题与是否可关闭由代码里的 NotifyTopic 定义——
--   奖惩 / 违规、活动取消这类「不知道就会吃亏」的提醒不可关闭，存了关闭也不生效。
--
-- 【本文件形态】三条 CREATE TABLE（新表）+ 一个权限点。

CREATE TABLE user_address (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    volunteer_id    BIGINT       NOT NULL COMMENT '所属志愿者',
    recv_name       VARCHAR(32)  NOT NULL COMMENT '收件人',
    recv_phone      VARCHAR(255) NOT NULL COMMENT '收件电话（密文）',
    region          VARCHAR(128) NOT NULL COMMENT '省市区（前端选择器拼好的一段文字）',
    detail          VARCHAR(255) NOT NULL COMMENT '详细地址',
    is_top          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否置顶',
    top_time        DATETIME              DEFAULT NULL COMMENT '置顶时间',
    create_time     DATETIME     NOT NULL COMMENT '创建时间',
    update_time     DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    active_top_key  BIGINT GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 AND is_top = 1 THEN volunteer_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_top (active_top_key),
    KEY idx_volunteer (volunteer_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '收货地址（Row 40）';

CREATE TABLE user_center_content (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    content_key  VARCHAR(32)   NOT NULL COMMENT 'insurance 我的保险 / customer-service 联系客服',
    title        VARCHAR(128)           DEFAULT NULL COMMENT '标题',
    body         TEXT                   DEFAULT NULL COMMENT '正文（客服电话写在这里）',
    images       VARCHAR(3200)          DEFAULT NULL COMMENT '图片 URL，逗号分隔，最多 6 张（客服二维码 / 保单图片）',
    update_by    BIGINT                 DEFAULT NULL COMMENT '最后修改人 admin_user.id',
    create_time  DATETIME      NOT NULL COMMENT '创建时间',
    update_time  DATETIME               DEFAULT NULL COMMENT '更新时间',
    is_deleted   TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除（不提供删除入口）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_content_key (content_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '个人中心内容（Row 42 / Row 48，后台设置）';

CREATE TABLE volunteer_notify_pref (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    volunteer_id  BIGINT      NOT NULL COMMENT '志愿者',
    topic         VARCHAR(32) NOT NULL COMMENT '提醒话题（NotifyTopic 的名字）',
    sms_enabled   TINYINT     NOT NULL DEFAULT 1 COMMENT '是否接收短信提醒',
    create_time   DATETIME    NOT NULL COMMENT '创建时间',
    update_time   DATETIME             DEFAULT NULL COMMENT '更新时间',
    is_deleted    TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除（不使用）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_volunteer_topic (volunteer_id, topic),
    KEY idx_topic_disabled (topic, sms_enabled, volunteer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订阅通知偏好（Row 48，只存和默认不同的）';

INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('user:center-content', '个人中心内容设置（我的保险 / 联系客服）', 'user', 2, 77, NOW(), 0);
