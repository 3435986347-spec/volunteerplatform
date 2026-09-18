-- 赞助商评价（V4 爱心企业批·社区段）：xlsx Row 74「评价板块：含活动评价、志愿者评价、负责人评价、商品评价、赞助商评价」+「删除、屏蔽功能」。
--
-- 【一单一评】评的是「这次兑换的体验」，靠兑换单唯一键；兑换单必须是本人的、已领取的、商品由这家企业赞助（判定在服务层，donate 只读查询）。
-- 【屏蔽 ≠ 删除】屏蔽是后台把它对外藏起来（作者与后台仍看得到、可恢复），删除是逻辑删除。两件事分开记，Row 74 原文就是两个动作。
-- 【本文件形态】建表 + 权限点 INSERT，新库上没有失败的可能。

CREATE TABLE enterprise_review (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    enterprise_id  BIGINT       NOT NULL COMMENT '被评价的企业',
    volunteer_id   BIGINT       NOT NULL COMMENT '评价人',
    order_id       BIGINT       NOT NULL COMMENT '兑换单（一单一评）',
    rating         TINYINT      NOT NULL COMMENT '评分 1~5',
    content        VARCHAR(500) DEFAULT NULL COMMENT '评价内容',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 正常 / 1 已屏蔽（后台藏起来，作者与后台仍可见）',
    hidden_by      BIGINT       DEFAULT NULL COMMENT '屏蔽人 admin_user.id',
    hidden_time    DATETIME     DEFAULT NULL COMMENT '屏蔽时间',
    hidden_reason  VARCHAR(255) DEFAULT NULL COMMENT '屏蔽原因',
    create_time    DATETIME     NOT NULL COMMENT '评价时间',
    update_time    DATETIME     DEFAULT NULL COMMENT '更新时间',
    is_deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    active_order_key BIGINT GENERATED ALWAYS AS (CASE WHEN is_deleted = 0 THEN order_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_order (active_order_key),
    KEY idx_enterprise (enterprise_id, status, id),
    KEY idx_volunteer (volunteer_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '赞助商评价（Row 74）';

INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('enterprise:review', '赞助商评价管理（查看 / 屏蔽 / 恢复 / 删除）', 'enterprise', 2, 104, NOW(), 0);
