-- 商品「只能使用指定卷才能兑换」（V3 卷批，Row 8 F）。
--
-- 【为什么单独一个文件】对已有表的 ALTER 与 V46 的新建表分开：MySQL 的 DDL 各自隐式提交，
--   同文件里「前一条成功、这一条失败」会让库停在做了一半又无法重跑的状态（V33/V34 那一课）。
--
-- 【语义】NULL = 不要求卷（照常用积分兑换，可选用满减卷）；非 NULL = 下单**必须**使用一张
--   coupon_id 等于该值的卷，没有就不能兑换。要求的可以是兑换卷，也可以是满减卷。
--
-- 【改它要退回重审】它是「谁能买、花多少」的一部分，与所需积分同级。MallGoodsService.update
--   已对已上架 / 已停用的商品整体退回待审核，本列随同一个入口修改，不另开口子。

ALTER TABLE mall_goods
    ADD COLUMN require_coupon_id BIGINT DEFAULT NULL
        COMMENT '必须使用的卷定义 mall_coupon.id；NULL=不要求（Row 8 F「没有卷就不能兑换」）'
        AFTER sponsor_name;
