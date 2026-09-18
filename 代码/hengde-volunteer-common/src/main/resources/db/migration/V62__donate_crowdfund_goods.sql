-- 项目众筹·捐物（V3 捐款批）。
--
-- 【需求出处】Row 16「有两种众筹方式，一个是捐款，一个是捐物……捐物需要他们提供捐赠物资名称，快递公司和快递单号
--   （需要对接物流接口，显示物流轨迹）」。
--
-- 【物资流转不另建表】复用捐书批的地基：运单 biz_type = 3（V50 早已预留），biz_id = 众筹项目 id。
--   到货 / 核对 / 专属码 / 装箱 / 送达 / 不合格退回全部沿用；物流轨迹与订阅推送也一样接得上。
--
-- 【一个项目收不收钱、收不收物，分开两列】Row 16 说「需要复制两个大板块」——同一个项目可能只收钱、只收物或都收。
--   存量项目按「只收钱」回填（accept_money 默认 1、accept_goods 默认 0），与结对批时的行为一致。
--
-- 【收件信息是协会的，不是 PII】寄物资的地址与电话是项目的公开信息（捐赠人要照着寄），明文存储。
--   顺丰等查轨迹要的收件电话就取这里。
--
-- 【本文件形态】一条 ALTER。

ALTER TABLE donate_crowdfund
    ADD COLUMN accept_money TINYINT      NOT NULL DEFAULT 1 COMMENT '是否接受捐款' AFTER raised_amount,
    ADD COLUMN accept_goods TINYINT      NOT NULL DEFAULT 0 COMMENT '是否接受捐物' AFTER accept_money,
    ADD COLUMN goods_needed VARCHAR(512)          DEFAULT NULL COMMENT '需要哪些物资（给捐物的人看）' AFTER accept_goods,
    ADD COLUMN recv_name    VARCHAR(64)           DEFAULT NULL COMMENT '物资收件人（接受捐物时必填）' AFTER goods_needed,
    ADD COLUMN recv_phone   VARCHAR(32)           DEFAULT NULL COMMENT '物资收件电话（协会的，明文；查轨迹也用它）' AFTER recv_name,
    ADD COLUMN recv_address VARCHAR(255)          DEFAULT NULL COMMENT '物资收件地址' AFTER recv_phone;
