-- 申诉凭证图片（V2 联调 P2-01）。
--
-- 存逗号分隔的 URL 串而不是另建一张 appeal_image 表：一次申诉最多 6 张、
-- 只跟着申诉一起读写、从不单独查询，建表带来的 join 与生命周期管理没有收益。
-- 上限由 RewardPunishService 在写入前校验，不靠列宽兜底——
-- 靠列宽的话超出会被 MySQL 静默截断（非严格模式）或抛一个指向列名的错，
-- 两种都不会告诉用户「最多传 6 张」。
--
-- 1024 的宽度：6 个 URL,每个约 150 字符（对象存储的 key 含日期目录与随机名），
-- 加分隔符约 900，留出余量。
ALTER TABLE honor_reward_punish
    ADD COLUMN appeal_images VARCHAR(1024) DEFAULT NULL
        COMMENT '申诉凭证图片 URL，逗号分隔，最多 6 张' AFTER appeal_reason;
