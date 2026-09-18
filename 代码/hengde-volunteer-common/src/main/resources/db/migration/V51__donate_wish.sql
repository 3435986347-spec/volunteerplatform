-- 圆梦微心愿（V3 微心愿批）。
--
-- 【需求出处】xlsx Row 12（心愿池 / 已认领 / 已实现 / 榜单；详情字段；「关键信息需要自动打*号」
--   「捐赠人认领之后就给他显示该心愿全部资料」「认领微心愿则需必须注册通过志愿者」「查看心愿必须登录系统并验证手机号」；
--   G 列物资流转：后台导入 → 认领 → 录入物资 → 包裹 → 到货 → 核对 → 专属条码 → 反馈发放图片，全程对捐赠人公开）、
--   Row 35（微心愿中心）、Row 18（排行榜·微心愿板块，V2 预留的 rank_type = 4 本批放行）。
--
-- 【为什么是 V51】捐书批占了 V50（2026-09-16）。
--
-- 【受助人是未成年人：姓名与学校密文存储】与 volunteer.phone 同一纪律（CryptoUtil AES-GCM）。
--   「认领前打 *、认领后给认领人看全部资料」的授权判定落在服务端——库里是密文，
--   前端不显示并不能代替服务端不下发。性别 / 年龄 / 年级单看不足以识别个人，明文存储（列表要按它们展示）。
--
-- 【物资流转复用捐书批的地基】微心愿的运单与物资就是 donate_shipment / donate_item，biz_type = 2，
--   biz_id = 认领记录 id（不是心愿 id）：认领可以被取消、心愿会被别人重新认领，
--   挂在认领上才分得清「这一包是谁寄给这个心愿的」。
--
-- 【一个心愿同一时刻只能被一个人认领】生成列 active_wish_key 只在「认领中 / 已实现」时取值，
--   写法同 V9 uk_active_volunteer / V28 uk_active_grant：取消或撤销认领即释放，心愿回到心愿池。
--   不靠「先查有没有人认领再插」——两个人同时点认领，后者必漏。
--
-- 【什么算「圆了一个心愿」】《协会待确认清单-v3》⑯ 默认按「已实现」算（认领可以取消，按认领算会让榜单反复抖动）。
--   排行榜数据源是认领记录的 realize_time，走 idx_status_realize。
--
-- 【本文件形态】两张新表 + 一条 INSERT，同 V41 / V46 / V50。

CREATE TABLE donate_wish (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    wish_no          VARCHAR(32)  NOT NULL COMMENT '心愿编号（导入时可给；不给由系统生成）',
    title            VARCHAR(128) NOT NULL COMMENT '标题（心愿）',
    content          VARCHAR(512)          DEFAULT NULL COMMENT '心愿内容',
    story            TEXT                  DEFAULT NULL COMMENT '心愿故事',
    image_url        VARCHAR(512)          DEFAULT NULL COMMENT '图片',
    child_name       VARCHAR(255) NOT NULL COMMENT '受助人姓名（**密文**，未成年人 PII）',
    child_gender     TINYINT               DEFAULT NULL COMMENT '性别 1男/2女',
    child_age        TINYINT               DEFAULT NULL COMMENT '年龄',
    child_school     VARCHAR(512)          DEFAULT NULL COMMENT '学校（**密文**）',
    child_grade      VARCHAR(32)           DEFAULT NULL COMMENT '年级',
    report_org_id    BIGINT                DEFAULT NULL COMMENT '上报单位（donate_recipient_org，清单⑤的同一类主数据）',
    report_org_name  VARCHAR(128)          DEFAULT NULL COMMENT '【导入时快照】上报单位名',
    remark           VARCHAR(512)          DEFAULT NULL COMMENT '备注',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '0待认领/1已认领/2已实现/3已下架',
    realize_time     DATETIME              DEFAULT NULL COMMENT '实现时间',
    feedback_images  TEXT                  DEFAULT NULL COMMENT '物资发放反馈图片 URL，换行分隔（Row 12 G「给捐赠人反馈物资发放图片」）',
    create_by        BIGINT                DEFAULT NULL COMMENT '导入 / 创建人 admin_user.id',
    create_time      DATETIME     NOT NULL COMMENT '上传时间（Row 12「我的」列表要展示）',
    update_time      DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    active_no_key    VARCHAR(32) GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 THEN wish_no ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_no (active_no_key),
    KEY idx_status (status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '圆梦微心愿（Row 12）';

CREATE TABLE donate_wish_claim (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    wish_id         BIGINT       NOT NULL COMMENT '心愿 id',
    volunteer_id    BIGINT       NOT NULL COMMENT '认领人 volunteer.id',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0认领中/1已实现/2已取消（本人）/3已撤销（后台）',
    claim_time      DATETIME     NOT NULL COMMENT '认领时间',
    realize_time    DATETIME              DEFAULT NULL COMMENT '实现时间（排行榜按它切周期）',
    cancel_time     DATETIME              DEFAULT NULL COMMENT '取消 / 撤销时间',
    cancel_by       BIGINT                DEFAULT NULL COMMENT '撤销人 admin_user.id（本人取消为空）',
    cancel_reason   VARCHAR(512)          DEFAULT NULL COMMENT '撤销原因',
    create_time     DATETIME     NOT NULL COMMENT '创建时间',
    update_time     DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    active_wish_key BIGINT GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 AND status IN (0, 1) THEN wish_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_wish (active_wish_key),
    KEY idx_volunteer (volunteer_id, id),
    KEY idx_wish (wish_id, id),
    KEY idx_status_realize (status, realize_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '微心愿认领（Row 35 微心愿中心）';

-- 权限点：本批 1 个。导入 / 单独上传 / 下架 / 撤销认领 / 反馈发放 / 批量下载同一个 donate:wish。
-- 批量下载含未成年人资料，但能管心愿的人本就看得到全部资料（详情明文），拆一个导出点买不到隔离。
-- 改这里必须同步 OrganizationRbacTest.permissionsSeeded（60 → 61）。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('donate:wish', '微心愿管理', 'donate', 2, 69, NOW(), 0);
