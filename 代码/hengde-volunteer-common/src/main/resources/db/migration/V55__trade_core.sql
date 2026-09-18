-- 收付能力（V3 trade 批）：交易单 / 支付流水 / 退款流水。
--
-- 【需求出处】Row 8（商城快递费）、Row 16（众筹捐款）、Row 10（助学结对捐款）共用的平台能力；
--   纸质证书（honor 第 4C 批，已冻结）、会员费（Row 5）、SaaS 增值收费（Row 84）日后也走这里。
--
-- 【金额一律用「分」存 INT，不用 DECIMAL 元】微信支付 APIv3 的金额单位就是分。
--   业务侧（结对认捐额、众筹目标额）用 DECIMAL 元是它们自己的事，**换算发生在调用方**，
--   并且只在一个方向上发生（元 → 分，进 trade）。两个地方各存一种单位、又各自四舍五入，
--   是对账时最难查的一类错——宁可在边界上写一次换算，也不要让两套单位在系统里并存。
--
-- 【一条业务记录同时只能有一张「活」交易单，不是永远只能有一张】
--   V3规划写的是 `uk_biz(biz_type, biz_no)`，那是简写。真按它建，**关单之后就再也付不了第二次**：
--   订单超时关闭是常态，而微信要求关单后换新的 out_trade_no 重下。故唯一键建在生成列上——
--   待支付 / 已支付时占键，已关闭 / 已退款时释放（写法同 V9 uk_active_volunteer / V52 uk_active_pair）。
--
-- 【out_trade_no 全局唯一且不复用】它是我们给微信的单号，复用会让两次支付在对账单上撞成一笔。
--
-- 【幂等落在唯一键上，不落在「先查再插」】重复回调、乱序回调、主动查单与扫描任务三条路都会写支付流水，
--   靠 `uk_transaction`（微信支付单号）挡重复——这与积分账本 uk_source、证书 uk_slot_cert 是同一条纪律。
--
-- 【本文件形态】四张新表 + 两条权限点 INSERT。

CREATE TABLE trade_order (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    out_trade_no    VARCHAR(64)  NOT NULL COMMENT '我方单号（给微信的 out_trade_no），全局唯一且不复用',
    biz_type        TINYINT      NOT NULL COMMENT '业务类型 1商城快递费/2众筹捐款/3助学结对捐款（纸质证书与会员费日后）',
    biz_no          VARCHAR(64)  NOT NULL COMMENT '业务单据号（如兑换单 id、结对登记 id），由调用方给',
    volunteer_id    BIGINT                DEFAULT NULL COMMENT '付款人 volunteer.id；企业端日后可空',
    subject         VARCHAR(128) NOT NULL COMMENT '商品 / 项目名快照——事后改名不影响已下的单',
    amount          INT          NOT NULL COMMENT '金额【分】',
    refunded_amount INT          NOT NULL DEFAULT 0 COMMENT '已退金额【分】',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0待支付/1已支付/2已关闭/3已退款/4部分退款',
    channel         TINYINT      NOT NULL DEFAULT 1 COMMENT '渠道 1微信小程序支付',
    expire_time     DATETIME     NOT NULL COMMENT '过期时刻（TTL 默认 15 分钟，各 bizType 可覆盖）',
    pay_time        DATETIME              DEFAULT NULL COMMENT '支付成功时刻（以微信的 success_time 为准）',
    close_time      DATETIME              DEFAULT NULL COMMENT '关单时刻',
    transaction_id  VARCHAR(64)           DEFAULT NULL COMMENT '微信支付单号（查单与对账用）',
    remark          VARCHAR(512)          DEFAULT NULL COMMENT '备注',
    create_by       BIGINT                DEFAULT NULL COMMENT '下单发起人（后台代下单时记 admin_user.id）',
    create_time     DATETIME     NOT NULL COMMENT '创建时间',
    update_time     DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    -- 「活」交易单：待支付或已支付时占位，关闭 / 退款后释放，于是关单之后可以重新下单
    active_biz_key  VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 AND status IN (0, 1) THEN CONCAT(biz_type, ':', biz_no) ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_out_trade_no (out_trade_no),
    UNIQUE KEY uk_active_biz (active_biz_key),
    -- 分钟级扫描「已发起未终态且已过期 / 该查单了」走这条（前导列是状态，扫描集合不随历史单量增长）
    KEY idx_status_expire (status, expire_time),
    KEY idx_biz (biz_type, biz_no),
    KEY idx_volunteer (volunteer_id, id),
    KEY idx_transaction (transaction_id),
    -- 每日对账按「支付时刻 / 关单时刻」切一天，两条都要走索引：已支付与已关闭的单只增不减，
    -- 没有这两条，对账的代价随全部历史单量而不是随那一天增长（V25 / V31 记过同一课）。
    -- 两列只在进入对应状态时写入，故不必把 status 放进前导列
    KEY idx_pay_time (pay_time),
    KEY idx_close_time (close_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '交易单（V3 trade 批）';

CREATE TABLE trade_payment (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    trade_order_id BIGINT      NOT NULL COMMENT '交易单 id',
    transaction_id VARCHAR(64) NOT NULL COMMENT '微信支付单号——**幂等键**：重复回调、查单、扫描都往这里写',
    amount         INT         NOT NULL COMMENT '实付金额【分】（**必须与交易单比对，不符一律拒绝**）',
    payer_openid   VARCHAR(64)          DEFAULT NULL COMMENT '付款人 openid',
    success_time   DATETIME             DEFAULT NULL COMMENT '微信返回的支付完成时刻',
    -- 报文留底：对账与纠纷时唯一能复盘的东西。**不存敏感字段之外的解密明文以外的内容**由服务层保证
    raw_json       MEDIUMTEXT           DEFAULT NULL COMMENT '回调 / 查单的原始报文（解密后）',
    source         TINYINT     NOT NULL DEFAULT 1 COMMENT '来源 1回调/2主动查单/3扫描任务——三条路走同一个方法',
    create_time    DATETIME    NOT NULL COMMENT '创建时间',
    update_time    DATETIME             DEFAULT NULL COMMENT '更新时间',
    is_deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_transaction (transaction_id),
    KEY idx_order (trade_order_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '支付流水（V3 trade 批）';

CREATE TABLE trade_refund (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    trade_order_id BIGINT      NOT NULL COMMENT '交易单 id',
    out_refund_no  VARCHAR(64) NOT NULL COMMENT '我方退款单号，全局唯一且不复用',
    refund_id      VARCHAR(64)          DEFAULT NULL COMMENT '微信退款单号',
    amount         INT         NOT NULL COMMENT '退款金额【分】（默认整单，粒度见《清单-v3》⑭）',
    status         TINYINT     NOT NULL DEFAULT 0 COMMENT '0处理中/1成功/2失败/3已关闭',
    reason         VARCHAR(512)         DEFAULT NULL COMMENT '退款原因（后台必填，给对账与纠纷看）',
    operator_id    BIGINT               DEFAULT NULL COMMENT '发起人 admin_user.id',
    success_time   DATETIME             DEFAULT NULL COMMENT '退款成功时刻',
    raw_json       MEDIUMTEXT           DEFAULT NULL COMMENT '回调 / 查询的原始报文（解密后）',
    create_time    DATETIME    NOT NULL COMMENT '创建时间',
    update_time    DATETIME             DEFAULT NULL COMMENT '更新时间',
    is_deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_out_refund_no (out_refund_no),
    KEY idx_order (trade_order_id, id),
    KEY idx_refund_id (refund_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '退款流水（V3 trade 批）';

-- 对账记录：每一次对账（每日定时 + 后台手动）的结果都落库。
-- 【为什么不只写日志】对账是支付回写的第四道，它的全部价值在于「差异被人看到」。
--   只打一行 ERROR 等于把「钱收了、单没发货」变成一条无人读的日志——
--   CertificateReconcileJob 那一批记过同一课（catch + log 不算最终一致性）。
-- 【跳过也要落一行】渠道未开通时 skipped=1：「今天没有对过」本身就是要让人看到的事实，
--   没有这一行，读的人分不清「对过且一致」与「根本没对」。
-- 【不设唯一键】多实例各跑一次只是多一行结果相同的记录；设了唯一键反而让「重跑一次」变成报错。
CREATE TABLE trade_reconcile_run (
    id                 BIGINT     NOT NULL AUTO_INCREMENT COMMENT '主键',
    window_from        DATETIME   NOT NULL COMMENT '对账区间起（含）',
    window_to          DATETIME   NOT NULL COMMENT '对账区间止（不含）',
    trigger_type       TINYINT    NOT NULL COMMENT '触发方式 1每日定时/2后台手动',
    operator_id        BIGINT              DEFAULT NULL COMMENT '手动触发人 admin_user.id；定时为空',
    skipped            TINYINT    NOT NULL DEFAULT 0 COMMENT '1=渠道未开通，**什么都没有核对过**',
    local_paid_count   INT        NOT NULL DEFAULT 0 COMMENT '区间内本地记为已支付（含已退款）的单数',
    local_closed_count INT        NOT NULL DEFAULT 0 COMMENT '区间内本地已关闭的单数',
    matched_count      INT        NOT NULL DEFAULT 0 COMMENT '核对一致的单数',
    mismatch_count     INT        NOT NULL DEFAULT 0 COMMENT '差异条数（列表页直接按它筛「有问题的那几天」）',
    mismatches_json    MEDIUMTEXT          DEFAULT NULL COMMENT '差异明细（JSON 数组）',
    create_time        DATETIME   NOT NULL COMMENT '创建时间',
    update_time        DATETIME            DEFAULT NULL COMMENT '更新时间',
    is_deleted         TINYINT    NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_window (window_from, id),
    KEY idx_mismatch (mismatch_count, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对账记录（V3 trade 批）';

-- 权限点：本批 2 个。查看与退款分开——退款是把钱退出去的动作，与「看一眼单子」不是一回事
-- （同 V24「查看与调整分开」的口径）。改这里必须同步 OrganizationRbacTest.permissionsSeeded（62 → 64）。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('trade:order', '交易单查看 / 查单 / 关单 / 对账', 'trade', 2, 71, NOW(), 0);
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('trade:refund', '退款', 'trade', 2, 72, NOW(), 0);
