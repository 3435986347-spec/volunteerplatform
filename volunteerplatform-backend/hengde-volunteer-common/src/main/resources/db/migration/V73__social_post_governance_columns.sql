-- 社区治理（V4 社区治理批）：帖子上的审核 / 风控 / 隐藏 / 置顶列。一条 ALTER（多列 ADD 是一条语句）。
--
-- review_level：已经通过了几级审核（0 起）；review_status 仍是 0 待审核 / 1 通过 / 2 驳回。
-- keyword_hit：发帖 / 修改时命中风控关键词——「先藏后审」（D7 / Q1）：别人看不到，本人看得到，审核通过后放出来；审核队列里插队。
-- admin_hidden：后台「隐藏」——除作者外谁都看不到，与删除不同的是可以恢复。
-- pinned / pin_time：后台「置顶」——最新与官方页签里排在最前（后置顶的在前）。

ALTER TABLE social_post
    ADD COLUMN review_level  TINYINT      NOT NULL DEFAULT 0 COMMENT '已通过几级审核' AFTER review_status,
    ADD COLUMN keyword_hit   TINYINT      NOT NULL DEFAULT 0 COMMENT '1 命中风控关键词（先藏后审）' AFTER review_level,
    ADD COLUMN keyword_hits  VARCHAR(255) DEFAULT NULL COMMENT '命中的关键词（给审核员看）' AFTER keyword_hit,
    ADD COLUMN admin_hidden  TINYINT      NOT NULL DEFAULT 0 COMMENT '1 后台隐藏' AFTER keyword_hits,
    ADD COLUMN pinned        TINYINT      NOT NULL DEFAULT 0 COMMENT '1 置顶' AFTER admin_hidden,
    ADD COLUMN pin_time      DATETIME     DEFAULT NULL COMMENT '置顶时间' AFTER pinned,
    ADD KEY idx_review_queue (review_status, is_deleted, keyword_hit, create_time);
