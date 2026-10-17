-- 积分商城·自提点（协会答复五 丙-3，2026-10-16）。
--
-- 【需求】「后台发布积分商品，兑换时可勾选自提，并选择可兑换的自提点」——自提点有多个、由后台维护，
--   每件商品选它在哪几个自提点可领，志愿者兑换时选一个。V41 时只有配置里的一个固定自提点
--   （当时注释就写了：协会答「多个」时加一张表即可，订单上的三列快照不用改），本文件就是那张表。
-- 【订单仍存快照】兑换时把所选自提点的名称 / 地址 / 电话快照进 mall_order 的 pickup_site_* 三列，
--   自提点改名、停用、删除都不改写历史单据。
-- 【本文件形态】一条 CREATE TABLE。

CREATE TABLE IF NOT EXISTS mall_pickup_point (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL COMMENT '名称，如「协会办公室」',
    address     VARCHAR(255) NOT NULL COMMENT '地址',
    phone       VARCHAR(32)  NULL COMMENT '联系电话',
    open_hours  VARCHAR(128) NULL COMMENT '开放时间说明',
    enabled     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用（停用的不能再被选，已兑换的单不受影响）',
    sort        INT          NOT NULL DEFAULT 0,
    create_time DATETIME     NULL,
    update_time DATETIME     NULL,
    is_deleted  TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分商城自提点';
