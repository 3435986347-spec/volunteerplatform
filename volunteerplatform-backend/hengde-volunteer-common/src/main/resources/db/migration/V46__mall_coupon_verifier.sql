-- 卷与核销员（V3 卷批）。
--
-- 【需求出处】xlsx Row 8 F：「商品只能使用指定卷才能兑换功能，没有卷就不能兑换」
--   「发放卷功能、批量发卷功能，如指定商品兑换卷、积分满减卷」
--   「企业可以设置某一个志愿者为企业核销员，企业核销员可以在前端用扫一扫功能给申请兑换的志愿者核销商品」；
--   Row 8 C「我的卷：后台发放的兑换卷、满减卷」。
--
-- 【为什么是 V46】2026-09-16 跨分支核对（含远端）：全部分支最大号 V45（V2 联调补口 V43–V45）。
--
-- 【卷的三条规则取《协会待确认清单-v3》④ 的默认】
--   ① 满减的「满」按**积分**算（按所下规格的所需积分，不按现金——纯积分闭环里没有现金）；
--   ② **不可叠加**，一单一卷——mall_order 上只有一个 coupon_grant_id 列，结构上就叠加不了；
--   ③ **有有效期，到期即失效**。「已过期」**不落库、按时间现算**（expire_time <= NOW()），
--      与处置 V32「到期靠比时间不靠 cron 改状态位」同一条：定时任务漏跑一次，过期的卷就还能用。
--   答复若与默认不同，① 改一个比较、② 加一张关联表、③ 去掉一个条件——都不推翻本表。
--
-- 【两种卷的语义】
--   type=1 指定商品兑换卷：只能用于 goods_id 那件商品，**全额抵扣所需积分**（凭卷兑换，0 积分）。
--          discount_points 恒为 NULL——「兑换卷」字面就是拿卷换东西，抵一部分的是满减卷。
--   type=2 积分满减卷：规格所需积分 >= threshold_points 时减 discount_points；goods_id 为 NULL 表示全场通用。
--   「商品只能使用指定卷才能兑换」落在 mall_goods.require_coupon_id（V47），与卷类型正交：
--   要求的可以是兑换卷，也可以是满减卷。
--
-- 【发出去的卷快照条款】mall_coupon_grant 把类型 / 商品 / 门槛 / 抵扣 / 有效期**全部快照**。
--   理由与订单三项快照（D8）、勋章「附带积分取发起时快照」同源：卷发到志愿者手里之后，
--   管理员再改卷定义（把减 20 改成减 5、把有效期提前），**不该追溯改写已经发出去的卷**。
--   另一条路是「卷一旦发出就锁死定义」——但那要在 UPDATE 里判断「有没有发放记录」，
--   与并发发放之间又是一个先查后改的窗口；快照让两者根本不相关。
--
-- 【批量发卷的幂等】request_id + volunteer_id 唯一。request_id 由前端每次打开发卷弹窗生成，
--   双击 / 弱网重放 / 并发重投都只会发一次。同一 request_id 若被用于**另一张卷**，
--   服务层按「载荷不同的撞键」报冲突（积分账本那条纪律），不静默吞掉。
--   ⚠️ request_id 用 utf8mb4_0900_bin：默认排序规则既忽略大小写又忽略重音，
--   库里的「相等」与 Java 的「相等」会不重合（V34 那一课）；服务层另把它限死在 ASCII 安全字符集。
--   本迁移要 MySQL 8.0.17（utf8mb4_0900_bin），由 DatabaseVersionGuard 在 Flyway 之前校验。
--
-- 【核销员仍是志愿者】Row 8 F 原文「设置某一个志愿者为企业核销员」。enterprise 不在 V3，
--   故 V3 由后台指派、enterprise_id 恒为 NULL（=可核销全部商品）；V4 爱心企业自助指派时
--   才填企业 id，作用域收窄到该企业赞助的商品（V3规划·承重条款 3）。
--   一人至多一条**有效**核销员记录：生成列 active_volunteer_key 只在未删行上取值，
--   写法同 V31 active_scope_key——裸 UNIQUE(volunteer_id) 会让软删行继续占键，
--   撤掉某人的核销员资格后就再也加不回来。
--
-- 【本文件形态】三张新表 + 一条 INSERT，同 V41 / V28：新建表失败只会失败在第一条，
--   不存在「做了一半又无法重跑」的中间态之外的新风险；对已有表的 ALTER 一律另起文件（V47 / V48）。

CREATE TABLE mall_coupon (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name             VARCHAR(64)  NOT NULL COMMENT '卷名称',
    type             TINYINT      NOT NULL COMMENT '1指定商品兑换卷（全额抵扣）/2积分满减卷',
    goods_id         BIGINT                DEFAULT NULL COMMENT '适用商品 mall_goods.id：type=1 必填；type=2 为 NULL=全场通用',
    threshold_points INT                   DEFAULT NULL COMMENT 'type=2：规格所需积分满多少可用',
    discount_points  INT                   DEFAULT NULL COMMENT 'type=2：减多少积分；type=1 恒为 NULL（全额抵扣）',
    valid_start      DATETIME     NOT NULL COMMENT '有效期起（含）',
    valid_end        DATETIME     NOT NULL COMMENT '有效期止（不含）',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用/0停用——停用只挡新发放，已发出的卷照常可用',
    description      VARCHAR(512)          DEFAULT NULL COMMENT '使用说明',
    create_by        BIGINT                DEFAULT NULL COMMENT '创建人 admin_user.id',
    create_time      DATETIME     NOT NULL COMMENT '创建时间',
    update_time      DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除；本表不提供删除入口（商品可能以 require_coupon_id 引用它），只能停用',
    PRIMARY KEY (id),
    KEY idx_status (status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分商城卷定义（Row 8 F）';

CREATE TABLE mall_coupon_grant (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    coupon_id        BIGINT       NOT NULL COMMENT '卷定义 mall_coupon.id',
    volunteer_id     BIGINT       NOT NULL COMMENT '持有人 volunteer.id',
    request_id       VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL
                                  COMMENT '发放批次幂等键（前端每次打开发卷弹窗生成）；二进制排序规则见文件头',
    coupon_name      VARCHAR(64)  NOT NULL COMMENT '【发放时快照】卷名称',
    type             TINYINT      NOT NULL COMMENT '【发放时快照】1兑换卷/2满减卷',
    goods_id         BIGINT                DEFAULT NULL COMMENT '【发放时快照】适用商品',
    threshold_points INT                   DEFAULT NULL COMMENT '【发放时快照】满减门槛',
    discount_points  INT                   DEFAULT NULL COMMENT '【发放时快照】抵扣积分，兑换卷为 NULL',
    valid_start      DATETIME     NOT NULL COMMENT '【发放时快照】有效期起（含）',
    expire_time      DATETIME     NOT NULL COMMENT '【发放时快照】到期时刻（不含）',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '0未使用/1已使用/2已作废；「已过期」不落库，按 expire_time 现算',
    used_order_id    BIGINT                DEFAULT NULL COMMENT '用在哪张兑换单上；退单时凭它 CAS 归还',
    used_time        DATETIME              DEFAULT NULL COMMENT '使用时间',
    grant_by         BIGINT                DEFAULT NULL COMMENT '发放人 admin_user.id',
    revoke_by        BIGINT                DEFAULT NULL COMMENT '作废人 admin_user.id',
    revoke_time      DATETIME              DEFAULT NULL COMMENT '作废时间',
    revoke_reason    VARCHAR(512)          DEFAULT NULL COMMENT '作废原因',
    create_time      DATETIME     NOT NULL COMMENT '发放时间',
    update_time      DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除；作废走 status=2 留痕，不走删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_request_volunteer (request_id, volunteer_id),
    KEY idx_volunteer_status (volunteer_id, status, expire_time),
    KEY idx_coupon (coupon_id, id),
    KEY idx_used_order (used_order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '卷发放记录（我的卷，Row 8 C）';

CREATE TABLE mall_verifier (
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    volunteer_id         BIGINT       NOT NULL COMMENT '核销员 volunteer.id',
    enterprise_id        BIGINT                DEFAULT NULL COMMENT '作用域：NULL=全部商品；V4 企业自助指派时为该企业 id，只能核销其赞助的商品',
    remark               VARCHAR(128)          DEFAULT NULL COMMENT '备注（如所在门店）',
    create_by            BIGINT                DEFAULT NULL COMMENT '指派人 admin_user.id',
    create_time          DATETIME     NOT NULL COMMENT '指派时间',
    update_time          DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 = 撤销核销员资格',
    -- 一人至多一条有效记录；软删行不占键，撤销后可重新指派（同 V31 active_scope_key）
    active_volunteer_key BIGINT GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 THEN volunteer_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_volunteer (active_volunteer_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商城核销员（Row 8 F，V3 由后台指派）';

-- 权限点：本批 1 个。发卷与卷定义同一个点——「能建卷却不能发」或反过来都没有现实场景。
-- 核销员指派复用 donate:verify（V41），不新增。改这里必须同步 OrganizationRbacTest.permissionsSeeded（57 → 58）。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('donate:coupon', '卷管理与发放', 'donate', 2, 66, NOW(), 0);
