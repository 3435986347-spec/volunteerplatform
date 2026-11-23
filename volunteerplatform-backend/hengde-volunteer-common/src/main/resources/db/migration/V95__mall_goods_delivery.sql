-- 积分商城·商品级领取方式（协会答复五 丙-3，2026-10-16）。
--
-- 【需求】「后台发布商品时必须选自提或快递，也可以同时选择。这个是一个商品设置，并不需要商品里面下面的单独设置」；
--   「也可同时勾选快递，并设置快递费或运费积分」；「虚拟商品只能设置自提……在发布商品的详情里面展示兑换二维码，让他联系协会兑换」。
-- 【allow_pickup / allow_express 可空】NULL＝这件商品还没按新口径设置过，沿用改造前的全局行为
--   （自提恒可、快递跟 hengde.donate.mall.express.enabled、快递费取配置）。存量商品因此不必回填也不会变样；
--   后台表单保存时两个都会写成 0/1。
-- 【快递费两列】express_fee_fen 现金【分】、express_points 运费积分，至少设一个；志愿者只能选商品给出的那一种 / 两种。
-- 【pickup_point_ids】逗号分隔的 mall_pickup_point.id；空＝不限定自提点（沿用配置里的那一个）。
-- 【本文件形态】一条 ALTER（同表多列是一条语句，要么全成要么全不成）。

ALTER TABLE mall_goods
    ADD COLUMN allow_pickup     TINYINT      NULL COMMENT '可自提 1/0；NULL=未按新口径设置（沿用全局）' AFTER require_coupon_id,
    ADD COLUMN allow_express    TINYINT      NULL COMMENT '可快递 1/0；NULL=未按新口径设置（沿用全局开关）' AFTER allow_pickup,
    ADD COLUMN pickup_point_ids VARCHAR(255) NULL COMMENT '可选自提点 id，逗号分隔；空=不限定' AFTER allow_express,
    ADD COLUMN express_fee_fen  INT          NULL COMMENT '快递费【分】（现金）；NULL=不收现金快递费' AFTER pickup_point_ids,
    ADD COLUMN express_points   INT          NULL COMMENT '运费积分；NULL=不收积分运费' AFTER express_fee_fen,
    ADD COLUMN virtual_goods    TINYINT      NOT NULL DEFAULT 0 COMMENT '虚拟商品 1/0：只能自提，详情展示兑换二维码' AFTER express_points,
    ADD COLUMN redeem_qr_url    VARCHAR(512) NULL COMMENT '虚拟商品的兑换二维码图片（本系统上传）' AFTER virtual_goods;
