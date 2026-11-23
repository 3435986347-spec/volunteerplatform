-- 志愿者证（V4 志愿者证批）：xlsx Row 26「生成类似身份证的一种志愿者身份证明，里面还会附带一个小程序码，其他人扫码的话能看得到他的志愿者信息」。
--
-- 【扫码令牌不是 volunteer.id】核验页免登录（扫码的人不一定是本平台用户），裸 id 可以顺着枚举，等于把全体志愿者名册公开（V4规划 D11）。
--   令牌是 24 字节随机数的 base64url＝32 个字符，正好是小程序码 scene 参数的上限。
-- 【另起一张表、不在 volunteer 上加列】令牌要能重置（证件截图外流时本人一键作废旧码），属 user 域；volunteer 表归 auth。
-- 【token 列用 ascii_bin】base64url 区分大小写，默认的 utf8mb4_0900_ai_ci 会把大小写不同的两个令牌当成同一个（V34 那一课）。
-- 【本文件形态】单条建表，新库上没有失败的可能。

CREATE TABLE user_volunteer_card (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    volunteer_id BIGINT      NOT NULL COMMENT '志愿者',
    token        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '扫码令牌（随机，重置即换）',
    issue_time   DATETIME    NOT NULL COMMENT '当前令牌签发时间',
    reset_count  INT         NOT NULL DEFAULT 0 COMMENT '重置次数',
    create_time  DATETIME    NOT NULL COMMENT '创建时间',
    update_time  DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_volunteer (volunteer_id),
    UNIQUE KEY uk_token (token)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '志愿者证（扫码核验令牌）';
