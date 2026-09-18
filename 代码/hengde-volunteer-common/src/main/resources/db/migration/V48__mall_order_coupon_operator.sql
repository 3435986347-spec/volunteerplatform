-- 兑换单：用卷快照 + 核销人类型（V3 卷批）。
--
-- 【一条 ALTER 带五个 ADD】同一张表的多个 ADD COLUMN 是一条语句，要么全成要么全不成（V43 同一写法）。
--
-- 【original_points 与 points 分开】用卷以后「规格标价」与「实际扣了多少分」不再相等：
--   points 从本版起专指**实际扣分**（退分金额以它为准，与 point_record 对得上），
--   original_points 是下单时的规格标价快照。只存一个的话，退单时要么退错数、要么还原不出标价。
--   存量行由 V49 回填 original_points = points（它们没有用过卷，两者本就相等）。
--
-- 【卷的三项快照】coupon_grant_id 定位退单时要归还的那张卷；coupon_name / coupon_deduct_points
--   是给「我的兑换」展示的快照——卷发放记录日后可能被作废，订单要还原得出「当时用了什么卷、抵了多少」。
--   与 D8 商品名 / 规格名 / 积分三项快照同一条理由。
--
-- 【pickup_operator_type】核销人不再只有管理员：卷批起核销员（志愿者）也能在小程序扫码核销。
--   pickup_operator 一列同时装 admin_user.id 与 volunteer.id，不带类型就无法区分——
--   与活动违规「记录人仅本活动志愿者负责人解析、否则 null，防 volunteer/admin 跨域同号错认」同一类问题。
--   1=管理员 / 2=核销员（志愿者）；未核销的单为 NULL。存量已核销行由 V49 回填 1（当时只有管理员能核销）。

ALTER TABLE mall_order
    ADD COLUMN original_points      INT         DEFAULT NULL COMMENT '【下单时快照】规格标价积分；points 从本版起专指实际扣分' AFTER points,
    ADD COLUMN coupon_grant_id      BIGINT      DEFAULT NULL COMMENT '所用的卷 mall_coupon_grant.id；退单时凭它归还' AFTER original_points,
    ADD COLUMN coupon_name          VARCHAR(64) DEFAULT NULL COMMENT '【下单时快照】所用卷名称' AFTER coupon_grant_id,
    ADD COLUMN coupon_deduct_points INT         DEFAULT NULL COMMENT '【下单时快照】卷抵扣了多少积分' AFTER coupon_name,
    ADD COLUMN pickup_operator_type TINYINT     DEFAULT NULL COMMENT '核销人类型 1管理员/2核销员（志愿者）；未核销为 NULL' AFTER pickup_operator;
