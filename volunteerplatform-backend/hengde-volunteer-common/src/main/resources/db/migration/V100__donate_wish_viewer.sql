-- 微心愿详情的查看记录（协会 2026-10-16 答复五 丁-7：「绑定了手机号就正常显示，后台收集他看这个心愿的手机号」）。
--
-- 看心愿详情本来就要已验手机号（V51 起），这里把「谁看过哪个心愿、用的哪个号」留下来给协会：
-- 一人一个心愿一行，再看只累加次数、刷新最近时间与号码。号码**密文**快照——之后换号、注销清号，这里仍是他看的那时候的号。

CREATE TABLE donate_wish_viewer (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    wish_id         BIGINT       NOT NULL COMMENT '心愿',
    volunteer_id    BIGINT       NOT NULL COMMENT '查看人 volunteer.id',
    phone           VARCHAR(255)          DEFAULT NULL COMMENT '【查看时快照】手机号（**密文**）',
    view_count      INT          NOT NULL DEFAULT 1 COMMENT '查看次数',
    first_view_time DATETIME     NOT NULL COMMENT '第一次查看',
    last_view_time  DATETIME     NOT NULL COMMENT '最近一次查看',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wish_volunteer (wish_id, volunteer_id),
    KEY idx_wish_last (wish_id, last_view_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '微心愿详情的查看记录（收集查看人手机号）';
