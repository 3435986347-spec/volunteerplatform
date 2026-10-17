-- 积分商城·兑换单记下所选自提点（协会答复五 丙-3，2026-10-16）。
--
-- 名称 / 地址 / 电话仍快照在 pickup_site_* 三列（自提点改了不改写历史）；这一列只是指回去，供后台按自提点筛单。
-- 【本文件形态】一条 ALTER。

ALTER TABLE mall_order
    ADD COLUMN pickup_point_id BIGINT NULL COMMENT '所选自提点 mall_pickup_point.id；NULL=未选（配置里的唯一自提点或快递单）' AFTER pickup_site_phone;
