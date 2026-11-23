-- 助学助困结对 + 项目众筹（V3 结对批）。
--
-- 【需求出处】xlsx Row 10（结对助学 / 助困 / 助残 / 结对成功；项目详情含图片、受助金额、已筹金额、参加人数；
--   「捐赠完成后受助方可给捐赠人写信（图文展示板块）」；「捐赠后需要自动生成证书」）、
--   Row 16（项目众筹：全部 / 进行中 / 已结束，图片、名称、预计金额、进度、捐赠人数）。
--
-- 【本批不碰支付】捐款走 trade 批与捐款批。所以这里的金额是「**认捐额**」而不是「已到账金额」：
--   `pledged_amount` 由结对登记累加，列名刻意不叫 raised/paid——叫 raised 会让前端把一个没到账的数
--   当成「已筹到的钱」展示给捐赠人看，那是会被追责的失真。真正的到账额等捐款批接上支付再加列。
--
-- 【参加人数不存列】按结对记录现算（`COUNT` 有效登记）。存一列就有两个口径，
--   而本项目已经在 points_award 与积分账本、totalEarned 与排行榜上各栽过一次。
--
-- 【受助人信息本批不建 PII 列】Row 10 只要求展示项目图片、金额与进度，没有要求展示受助人姓名 / 学校。
--   微心愿那边建密文列是因为 Row 12 明确要展示并打 `*`；这里没有那条需求，
--   凭空建一张存未成年人信息的表属于「设计超出需求」，要用时再加。
--
-- 【一人一项目至多一条有效登记】生成列唯一键（同 V9 uk_active_volunteer / V51 uk_active_wish）：
--   取消之后可以重新登记，不靠「先查再插」——并发下那必漏。
--
-- 【证书】结对成立即出证（《协会待确认清单-v3》⑨ 的默认），挂钩由 donate 发领域事件、honor 订阅；
--   证书表的幂等键在 V53 / V54 单独加（那是对既有表的 ALTER，与建表分文件，见下一条）。
--
-- 【本文件形态】四张新表 + 一条权限点 INSERT，同 V50 / V51。对既有表的 ALTER 不放这里：
--   DDL 各自隐式提交，「建表成功、ALTER 失败」会让库停在做了一半又无法重跑的状态。

CREATE TABLE donate_pair_project (
    id              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    title           VARCHAR(128)   NOT NULL COMMENT '项目名称',
    project_type    TINYINT        NOT NULL DEFAULT 1 COMMENT '类型 1结对助学/2结对助困/3结对助残',
    cover_url       VARCHAR(512)            DEFAULT NULL COMMENT '封面图',
    detail          TEXT                    DEFAULT NULL COMMENT '项目详情（图文）',
    target_amount   DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '受助金额（目标）',
    pledged_amount  DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '已认捐金额（**登记额，不是已到账**，见文件头）',
    status          TINYINT        NOT NULL DEFAULT 0 COMMENT '0草稿/1进行中/2已结对/3已结束',
    create_by       BIGINT                  DEFAULT NULL COMMENT '创建人 admin_user.id',
    create_time     DATETIME       NOT NULL COMMENT '创建时间',
    update_time     DATETIME                DEFAULT NULL COMMENT '更新时间',
    is_deleted      TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_status_type (status, project_type, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '助学助困结对项目（Row 10）';

CREATE TABLE donate_pair_record (
    id               BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_id       BIGINT         NOT NULL COMMENT '结对项目 id',
    volunteer_id     BIGINT         NOT NULL COMMENT '结对人 volunteer.id',
    amount           DECIMAL(12, 2) NOT NULL COMMENT '认捐金额',
    amount_type      TINYINT        NOT NULL DEFAULT 1 COMMENT '1指定金额/2全款',
    status           TINYINT        NOT NULL DEFAULT 0 COMMENT '0已登记待确认/1结对成立/2已取消',
    register_time    DATETIME       NOT NULL COMMENT '登记时间',
    established_time DATETIME                DEFAULT NULL COMMENT '结对成立时间（出证的触发点）',
    cancel_time      DATETIME                DEFAULT NULL COMMENT '取消时间',
    cancel_by        BIGINT                  DEFAULT NULL COMMENT '取消人 admin_user.id；本人取消为空',
    cancel_reason    VARCHAR(512)            DEFAULT NULL COMMENT '取消原因',
    remark           VARCHAR(512)            DEFAULT NULL COMMENT '登记留言（结对人写给协会的话）',
    create_time      DATETIME       NOT NULL COMMENT '创建时间',
    update_time      DATETIME                DEFAULT NULL COMMENT '更新时间',
    is_deleted       TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    -- 一人一项目至多一条「活」登记（待确认 / 已成立）；取消后释放，可重新登记
    active_pair_key  VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 AND status IN (0, 1) THEN CONCAT(project_id, ':', volunteer_id) ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_pair (active_pair_key),
    KEY idx_project_status (project_id, status, id),
    KEY idx_volunteer (volunteer_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '结对登记（Row 10；本批不含支付）';

CREATE TABLE donate_pair_letter (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_id     BIGINT       NOT NULL COMMENT '所属结对项目 id',
    -- 【可空是刻意的】写给某一位结对人的信填这一列（只有他看得到）；面向全体的公开信留空。
    -- 受助方不直接使用系统，信由协会代为录入（Row 10 只说「受助方可给捐赠人写信」，没有给受助方账号）。
    pair_record_id BIGINT                DEFAULT NULL COMMENT '收信的结对登记 id；为空=项目公开信',
    title          VARCHAR(128)          DEFAULT NULL COMMENT '标题',
    content        TEXT                  DEFAULT NULL COMMENT '正文',
    image_urls     TEXT                  DEFAULT NULL COMMENT '图片 URL，换行分隔（图文展示板块）',
    write_time     DATETIME              DEFAULT NULL COMMENT '来信时间（受助方写信那天，非录入时间）',
    create_by      BIGINT                DEFAULT NULL COMMENT '录入人 admin_user.id',
    create_time    DATETIME     NOT NULL COMMENT '录入时间',
    update_time    DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_project (project_id, id),
    KEY idx_pair (pair_record_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '受助方来信（Row 10 图文展示板块）';

CREATE TABLE donate_crowdfund (
    id             BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    title          VARCHAR(128)   NOT NULL COMMENT '项目名称',
    cover_url      VARCHAR(512)            DEFAULT NULL COMMENT '封面图',
    detail         TEXT                    DEFAULT NULL COMMENT '项目详情（图文）',
    target_amount  DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '预计金额（目标）',
    -- 本批无支付：这一列恒为 0，由捐款批接上 trade 之后写入。
    -- 【为什么先建】它是 Row 16 的「进度」分母之外的那一半，建表时留好比日后 ALTER 省事，
    -- 且值为 0 时前端显示「进度 0%」是如实的，不构成误导。
    raised_amount  DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '已筹金额（**到账额**，捐款批写入；本批恒为 0）',
    start_time     DATETIME                DEFAULT NULL COMMENT '开始时间',
    end_time       DATETIME                DEFAULT NULL COMMENT '结束时间',
    status         TINYINT        NOT NULL DEFAULT 0 COMMENT '0草稿/1进行中/2已结束',
    create_by      BIGINT                  DEFAULT NULL COMMENT '创建人 admin_user.id',
    create_time    DATETIME       NOT NULL COMMENT '创建时间',
    update_time    DATETIME                DEFAULT NULL COMMENT '更新时间',
    is_deleted     TINYINT        NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_status_time (status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '项目众筹（Row 16；捐款接 trade 在捐款批）';

-- 权限点：本批 1 个，结对项目与众筹项目共用（都是「项目管理」，没有「能管结对但不能管众筹」的现实分工）。
-- 改这里必须同步 OrganizationRbacTest.permissionsSeeded（61 → 62）。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('donate:project', '结对与众筹项目管理', 'donate', 2, 70, NOW(), 0);
