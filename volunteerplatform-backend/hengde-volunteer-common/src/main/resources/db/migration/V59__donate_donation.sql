-- 捐款（V3 捐款批）：众筹捐款（Row 16）与结对捐款（Row 10）的付款记录。
--
-- 【一张表装两种捐款】biz_type 与 trade 同码（2 众筹 / 3 结对），project_id 按 biz_type 指向
--   donate_crowdfund 或 donate_pair_project；结对捐款另挂 pair_record_id——钱是为「这一条结对」付的。
--   与运单 biz_type + biz_id 同一个形状；取来源名时同样要按 biz_type 分流，不能拿 project_id 直接去某一张表查。
--
-- 【金额存元（DECIMAL），另快照一份分（INT）】业务侧（众筹目标、结对认捐）全是元；进 trade 的那一刻换算成分，
--   换算结果也落在这一行（amount_fen）——对账时一眼看得出「交给微信的是多少分」，不必再算一遍。
--
-- 【状态】0 待支付 / 1 已到账 / 2 已取消（本人取消或超时）/ 3 已退款。
--   **「已到账」才计入已筹金额**；退款从已筹金额里减回去。
--
-- 【一条结对至多一笔待支付】active_pair_payment_key 只在「待支付且是结对捐款」时取值：结对捐款的金额是
--   「认捐额 − 已付」，两笔待支付同时付成就会超付；众筹捐款不设这条（每一笔都是独立的心意，也不占任何资源）。
--
-- 【发票只留字段与状态位】清单⑦默认：抬头 / 税号 / 是否需要 / 开票状态 / 发票号，不接税务。
--
-- 【本文件形态】一条 CREATE TABLE。

CREATE TABLE donate_donation (
    id                      BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    donation_no             VARCHAR(32)    NOT NULL COMMENT '对外单号',
    biz_type                TINYINT        NOT NULL COMMENT '2众筹捐款/3结对捐款（与 trade 的 biz_type 同码）',
    project_id              BIGINT         NOT NULL COMMENT '众筹项目 id 或结对项目 id（按 biz_type）',
    pair_record_id          BIGINT                  DEFAULT NULL COMMENT '结对捐款：为哪一条结对登记付的款',
    volunteer_id            BIGINT         NOT NULL COMMENT '捐款人 volunteer.id',
    project_title           VARCHAR(128)   NOT NULL COMMENT '【下单时快照】项目名',
    amount                  DECIMAL(12, 2) NOT NULL COMMENT '捐款金额（元）',
    amount_fen              INT            NOT NULL COMMENT '捐款金额（分），进 trade 的那个数',
    status                  TINYINT        NOT NULL DEFAULT 0 COMMENT '0待支付/1已到账/2已取消/3已退款',
    remark                  VARCHAR(255)            DEFAULT NULL COMMENT '捐款留言',
    pay_expire_time         DATETIME                DEFAULT NULL COMMENT '付款截止（仅待支付）',
    trade_order_id          BIGINT                  DEFAULT NULL COMMENT '付款成功的交易单 id',
    paid_time               DATETIME                DEFAULT NULL COMMENT '到账时间',
    cancel_reason           VARCHAR(255)            DEFAULT NULL COMMENT '取消原因（本人取消 / 超时）',
    need_invoice            TINYINT        NOT NULL DEFAULT 0 COMMENT '是否需要发票（清单⑦：只预留）',
    invoice_title           VARCHAR(128)            DEFAULT NULL COMMENT '发票抬头',
    invoice_tax_no          VARCHAR(32)             DEFAULT NULL COMMENT '纳税人识别号',
    invoice_status          TINYINT        NOT NULL DEFAULT 0 COMMENT '0不需要/1待开/2已开',
    invoice_no              VARCHAR(64)             DEFAULT NULL COMMENT '发票号（后台登记）',
    invoice_time            DATETIME                DEFAULT NULL COMMENT '登记开票时间',
    invoice_by              BIGINT                  DEFAULT NULL COMMENT '登记开票人 admin_user.id',
    refund_time             DATETIME                DEFAULT NULL COMMENT '退款时间',
    refund_by               BIGINT                  DEFAULT NULL COMMENT '退款发起人 admin_user.id',
    refund_reason           VARCHAR(512)            DEFAULT NULL COMMENT '退款原因',
    cash_refund_no          VARCHAR(64)             DEFAULT NULL COMMENT '原路退款单号（已受理）',
    cash_refund_error       VARCHAR(255)            DEFAULT NULL COMMENT '原路退款发起失败的原因（到收付页重试）',
    create_time             DATETIME       NOT NULL COMMENT '创建时间',
    update_time             DATETIME                DEFAULT NULL COMMENT '更新时间',
    is_deleted              TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    active_pair_payment_key BIGINT GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 AND status = 0 AND pair_record_id IS NOT NULL THEN pair_record_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_donation_no (donation_no),
    UNIQUE KEY uk_active_pair_payment (active_pair_payment_key),
    KEY idx_project_status (biz_type, project_id, status, id),
    KEY idx_pair_record (pair_record_id, status),
    KEY idx_volunteer (volunteer_id, id),
    -- 待支付同步扫「待支付且已过截止」，前导列是状态，集合只含在途的单
    KEY idx_status_pay_expire (status, pay_expire_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '捐款记录（Row 10 / Row 16，V3 捐款批）';
