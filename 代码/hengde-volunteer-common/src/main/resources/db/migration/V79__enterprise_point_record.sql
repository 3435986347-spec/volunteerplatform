-- 爱心企业积分账本（V4 爱心企业批·商品段，V4规划 D5）：Row 15 F「积分流转：志愿者用积分跟企业兑换的东西，企业将存下这个积分，后续用于兑换企业权益，比如广告位」。
--
-- 【独立账本、不写 point_record】point_record 是志愿者积分的唯一事实来源，混进企业流水会让排行榜、积分中心的每一条 SUM 都得记得排除企业行。
-- 【余额＝SUM(change_amount)】不冗余存余额列（同志愿者账本的理由）。
-- 【入账】来源 1＝兑换入账：兑换单变成「已领取」（现场核销 / 本人确认收货 / 系统自动确认）之后由定时任务补记，source_id＝兑换单 id，
--   uk_source 保幂等；金额＝这张单实际扣的积分减去其中抵扣快递费的部分（快递费不是企业的商品）。0 分不记。
-- 【调整】来源 2＝后台调整（兑换企业权益时扣减，或更正），source_id 为空，靠 request_id 幂等（同志愿者账本：多个 NULL 互不相同）。
-- 【本文件形态】建表 + 权限点 INSERT，新库上没有失败的可能。

CREATE TABLE enterprise_point_record (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    enterprise_id  BIGINT       NOT NULL COMMENT '企业',
    change_amount  INT          NOT NULL COMMENT '变动（正入负出）',
    source_type    TINYINT      NOT NULL COMMENT '1 兑换入账 / 2 后台调整',
    source_id      BIGINT       DEFAULT NULL COMMENT '来源单据：兑换单 id；后台调整为空',
    request_id     VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL COMMENT '后台调整的幂等键（ASCII 安全字符）',
    remark         VARCHAR(255) DEFAULT NULL COMMENT '说明（兑换入账带商品名与单号）',
    operator_id    BIGINT       DEFAULT NULL COMMENT '后台调整人 admin_user.id',
    create_time    DATETIME     NOT NULL COMMENT '记账时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_source (source_type, source_id),
    UNIQUE KEY uk_request_id (request_id),
    KEY idx_enterprise (enterprise_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '爱心企业积分账本';

INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('enterprise:points', '爱心企业积分（查看账本 / 调整 / 补记兑换入账）', 'enterprise', 2, 103, NOW(), 0);
