-- 积分商城·快递寄送与现金支付（V3 商城快递批）。
--
-- 【需求出处】Row 8 C「快递：志愿者可以选择支付快递费，也可以选择积分抵扣」+「部分商品需要积分和收款同时」。
--
-- 【快递费是计价器的输出，不是常量】V2 第 4 批定下的口径：应付 = 计价器输出。当前计价器返回一个配置值，
--   **下单时把结果快照进订单**（shipping_fee_fen）——日后调价不能改写历史单据的应付金额。
-- 【积分抵扣快递费】折算汇率（清单③，协会没给）是配置项，**下单时连汇率一起快照**（points_per_yuan）。
--   抵扣的积分**并进这张单的那一笔 EXCHANGE 流水**（points 列＝实际扣分总数），不另记一笔——
--   另记要么复用 source_id 撞 uk_source，要么新开来源码把「已使用积分」口径拆成两半（V3规划 D4）。
--
-- 【待支付（status=5）占着库存、积分与卷】与「下单即扣分占库存」同一口径：付款之前不占，同一件东西能被十个人同时卡在付款页。
--   代价是要有截止时刻（pay_expire_time）与补偿任务：超时没付的单自动取消、退分还库存还卷。
--   **截止时刻同时传给 trade**，交易单不会比这张单活得久——否则会在单子超时取消之后付进来。
--
-- 【收件电话密文】recv_phone 用 CryptoUtil 加密（同 donate_shipment.return_phone），只给本人与后台解密。
--
-- 【status 新增两档】5 待支付 / 6 已发货。快递单的「待领取（1）」展示为「待发货」；签收后同样落「已领取（3）」，
--   评价资格（须已领取）因此不用改。
--
-- 【两条索引】补偿任务扫「待支付且已过截止」、自动确认收货扫「已发货且发货已久」，前导列都是状态，
--   扫描集合只含在途的单，不随历史单量增长（V39 / V56 同形）。
--
-- 【本文件形态】一条 ALTER。

ALTER TABLE mall_order
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0 COMMENT '0待审核/1已通过（自提待领取·快递待发货）/2已驳回/3已领取（含快递已签收）/4已取消/5待支付/6已发货',
    MODIFY COLUMN delivery_type TINYINT NOT NULL DEFAULT 1 COMMENT '1自提/2快递（商城快递批起可达）',
    ADD COLUMN goods_cash_fen    INT          NOT NULL DEFAULT 0 COMMENT '【下单时快照】商品现金部分【分】' AFTER coupon_deduct_points,
    ADD COLUMN shipping_fee_fen  INT          NOT NULL DEFAULT 0 COMMENT '【下单时快照】快递费【分】（计价器输出）；自提为 0' AFTER goods_cash_fen,
    ADD COLUMN shipping_pay_type TINYINT               DEFAULT NULL COMMENT '快递费支付方式 1现金/2积分抵扣；自提为 NULL' AFTER shipping_fee_fen,
    ADD COLUMN points_per_yuan   INT                   DEFAULT NULL COMMENT '【下单时快照】积分抵扣汇率：1 元折多少积分' AFTER shipping_pay_type,
    ADD COLUMN shipping_points   INT          NOT NULL DEFAULT 0 COMMENT '【下单时快照】快递费折成的积分（已并入 points）' AFTER points_per_yuan,
    ADD COLUMN pay_cash_fen      INT          NOT NULL DEFAULT 0 COMMENT '应付现金【分】＝商品现金 +（现金付快递费时的）快递费' AFTER shipping_points,
    ADD COLUMN pay_expire_time   DATETIME              DEFAULT NULL COMMENT '付款截止（仅待支付的单）' AFTER pay_cash_fen,
    ADD COLUMN trade_order_id    BIGINT                DEFAULT NULL COMMENT '付款成功的交易单 trade_order.id' AFTER pay_expire_time,
    ADD COLUMN paid_time         DATETIME              DEFAULT NULL COMMENT '付款成功时间' AFTER trade_order_id,
    ADD COLUMN cash_refund_no    VARCHAR(64)           DEFAULT NULL COMMENT '驳回后发起的现金退款单号（已受理）' AFTER paid_time,
    ADD COLUMN cash_refund_error VARCHAR(255)          DEFAULT NULL COMMENT '现金退款发起失败的原因（后台据此到收付页重试）' AFTER cash_refund_no,
    ADD COLUMN recv_name         VARCHAR(64)           DEFAULT NULL COMMENT '【下单时快照】收件人' AFTER cash_refund_error,
    ADD COLUMN recv_phone        VARCHAR(255)          DEFAULT NULL COMMENT '【下单时快照】收件电话（密文，CryptoUtil）' AFTER recv_name,
    ADD COLUMN recv_address      VARCHAR(255)          DEFAULT NULL COMMENT '【下单时快照】收件地址' AFTER recv_phone,
    ADD COLUMN express_code      VARCHAR(32)           DEFAULT NULL COMMENT '发货快递公司编码（快递100 口径）' AFTER recv_address,
    ADD COLUMN express_company   VARCHAR(64)           DEFAULT NULL COMMENT '发货快递公司名称快照' AFTER express_code,
    ADD COLUMN express_no        VARCHAR(64)           DEFAULT NULL COMMENT '发货快递单号' AFTER express_company,
    ADD COLUMN ship_time         DATETIME              DEFAULT NULL COMMENT '发货时间' AFTER express_no,
    ADD COLUMN ship_by           BIGINT                DEFAULT NULL COMMENT '发货人 admin_user.id' AFTER ship_time,
    ADD KEY idx_status_pay_expire (status, pay_expire_time),
    ADD KEY idx_status_ship_time (status, ship_time);
