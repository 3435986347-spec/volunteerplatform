-- 积分商城核心（V3 商城批）：商品 / 规格 / 评价 / 兑换单。
--
-- 【为什么是 V41 而不是 V40】本文件原先占 V40。V2 第 5 批（奖惩两级审核）也要一个号，
--   而**那条分支要先合进 main**，所以它的号必须更小——否则库先跑到 V42，
--   V3 合进来时 Flyway 会看到 V40 < 当前版本而拒绝执行（outOfOrder 默认关闭，也不该为此打开）。
--   故 V2 占 V40，本文件顺延为 V41、兑换规则顺延为 V42。
--   ⚠️ 改名当时是安全的：这两个迁移**只在临时测试容器里跑过、从未在任何真实环境执行**，
--   且所在分支尚未合并——「迁移只要可能跑过就不能原地改」保护的是**已部署**的迁移。
--   ⚠️ 教训：**挑号时 `ls` 目录是不够的，跨分支占号 `ls` 看不见**（V27 那次是同分支内并行，
--   `ls` 还够用；这次不够）。正确做法见 CLAUDE.md 迁移纪律里的跨分支检查命令。
--
-- 【需求出处】xlsx Row 8「积分兑换」C 列与 F 列。本批只做**纯积分闭环 + 自提**；
--   快递与现金（Row 8 的「快递费可选支付或积分抵扣」）属商城快递批，要等 trade；
--   卷与核销员（Row 8 F「只能使用指定卷」「企业设核销员」）属卷批。
--
-- 【库存只存一处】stock 只在 mall_goods_spec 上，mall_goods 上**没有 stock 列**。
--   Row 8 C 的「我的兑换」明写要显示「商品规格」，故库存的粒度是规格而非商品。
--   两处都存库存 = 两个口径，是本项目反复栽的那类跟头（activity_attendance.points_award
--   与积分账本、totalEarned 与排行榜各记过一次）。
--   ⚠️ 「商品是否真有规格」尚待协会确认（清单 ⑫），默认按**有规格**设计：
--   只有一条规格时自然退化；反过来（先按无规格做、后来要规格）要迁移历史订单数据。
--
-- 【不建 qty 列】原型没有数量选择控件，一次兑换一件。这与 V2 为纸质证书记下的口径一致
--   （「原型无数量控件，qty 是推断的实现选择」）——没有依据的字段不先建。
--   故库存 CAS 恒为 stock - 1；将来若协会要一次多件，是加列 + 改 CAS，不是改单头结构。
--
-- 【订单快照三项】goods_name / spec_name / points 在下单时快照进 mall_order。
--   规格可改可删，事后要还原得出「当时买的是什么、花了多少分」。
--   与勋章「附带积分取发起时快照而非审核时现读」、纸质证书「自提点存快照文本不是悬空 id」
--   同源。删一个规格，历史订单不会变成空壳。
--
-- 【自提点存快照文本、不建自提点表】Row 8 C 写「给他选择兑换地址」（暗示多个），
--   但 V2 已为纸质证书判定不建自提点表（来源里只出现过一个固定值），而取货码要与纸质证书
--   共用形态。此处取同一形状：配置项 hengde.donate.mall.pickup-site.* + 下单快照三列文本。
--   ⚠️ 「一个还是多个」待协会确认（清单 ⑪）；答「多个」时**加一张自提点表即可，
--   这三列不用改**——这正是快照设计买到的东西。
--
-- 【为什么没有 CHECK / 特殊排序规则】本文件全部是新建表 + 一条 INSERT，没有版本相关语句，
--   故多条 DDL 同文件是安全的（同 V28 的 3 表 + 1 INSERT）。
--   「一个迁移文件只放一条有失败可能的语句」那条针对的是 V33/V34 那种版本相关特性
--   （CHECK 要 8.0.16+、utf8mb4_0900_bin 要 8.0.17+），本文件不涉及。

CREATE TABLE mall_goods (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name                  VARCHAR(128) NOT NULL COMMENT '商品名称',
    cover_url             VARCHAR(512)          DEFAULT NULL COMMENT '商品图片',
    detail                TEXT                  DEFAULT NULL COMMENT '商品详情（图文）',
    sponsor_enterprise_id BIGINT                DEFAULT NULL COMMENT '赞助企业 id；enterprise 模块未建，V3 恒为 NULL',
    sponsor_name          VARCHAR(128)          DEFAULT NULL COMMENT '赞助方名称快照——Row 15 F「后台可以以企业的名义代替企业发布」',
    status                TINYINT      NOT NULL DEFAULT 0 COMMENT '0草稿/1待审核/2已上架/3已停用/4驳回',
    hidden                TINYINT      NOT NULL DEFAULT 0 COMMENT '隐藏 0否/1是（Row 8 F「商品隐藏功能」）；与 status 正交，隐藏不改审核态',
    sort                  INT          NOT NULL DEFAULT 0 COMMENT '排序，纯展示',
    submit_time           DATETIME              DEFAULT NULL COMMENT '提交审核时间',
    review_by             BIGINT                DEFAULT NULL COMMENT '审核人 admin_user.id',
    review_time           DATETIME              DEFAULT NULL COMMENT '审核时间',
    reject_reason         VARCHAR(512)          DEFAULT NULL COMMENT '驳回原因',
    create_time           DATETIME     NOT NULL COMMENT '创建时间',
    update_time           DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_status_sort (status, sort),
    KEY idx_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分商品（Row 8）';

CREATE TABLE mall_goods_spec (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    goods_id    BIGINT       NOT NULL COMMENT '所属商品 mall_goods.id',
    name        VARCHAR(64)  NOT NULL COMMENT '规格名（Row 8 C「我的兑换」要展示）',
    points      INT          NOT NULL COMMENT '所需积分',
    stock       INT          NOT NULL DEFAULT 0 COMMENT '库存——**全系统唯一存放处**，mall_goods 上没有这一列',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_goods (goods_id, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品规格与库存';

CREATE TABLE mall_order (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no           VARCHAR(32)  NOT NULL COMMENT '对外单号（Row 8 C「我的兑换」要展示订单编号）',
    volunteer_id       BIGINT       NOT NULL COMMENT '兑换人 volunteer.id',
    goods_id           BIGINT       NOT NULL COMMENT '商品 id',
    spec_id            BIGINT       NOT NULL COMMENT '规格 id',
    goods_name         VARCHAR(128) NOT NULL COMMENT '**下单时快照**：商品名（规格可改可删，事后要还原得出）',
    spec_name          VARCHAR(64)  NOT NULL COMMENT '**下单时快照**：规格名',
    points             INT          NOT NULL COMMENT '**下单时快照**：所需积分（实际扣了多少分以 point_record 为准）',
    status             TINYINT      NOT NULL DEFAULT 0 COMMENT '0待审核/1已通过待领取/2已驳回/3已领取/4已取消',
    delivery_type      TINYINT      NOT NULL DEFAULT 1 COMMENT '1自提；2快递属商城快递批，本批不可达',
    pickup_site_name   VARCHAR(128)          DEFAULT NULL COMMENT '自提点快照·名称（取自配置，不是悬空 id）',
    pickup_site_addr   VARCHAR(255)          DEFAULT NULL COMMENT '自提点快照·地址',
    pickup_site_phone  VARCHAR(32)           DEFAULT NULL COMMENT '自提点快照·电话',
    pickup_code        VARCHAR(32)           DEFAULT NULL COMMENT '取货码，审核通过时生成；形态与纸质证书自提码共用（common 只出生成与校验）',
    pickup_time        DATETIME              DEFAULT NULL COMMENT '核销时间',
    pickup_operator    BIGINT                DEFAULT NULL COMMENT '核销人 admin_user.id',
    review_by          BIGINT                DEFAULT NULL COMMENT '审核人 admin_user.id',
    review_time        DATETIME              DEFAULT NULL COMMENT '审核时间',
    reject_reason      VARCHAR(512)          DEFAULT NULL COMMENT '驳回原因',
    create_time        DATETIME     NOT NULL COMMENT '下单时间',
    update_time        DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_pickup_code (pickup_code),
    KEY idx_volunteer_time (volunteer_id, create_time),
    KEY idx_status_time (status, create_time),
    KEY idx_spec (spec_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分兑换单（Row 8）';

-- 【评价的资格闸门在 order_id 上】Row 8 C 有「商品的评价 / 全部商品评价 / 我的评价」，
--   但没说谁能评。比照 AttendanceService.submitReview 那条「须实际签到才能评」的形状：
--   评价必须挂在一张属于本人的、已领取的兑换单上，uk_order 保证一单一评。
CREATE TABLE mall_goods_review (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    goods_id     BIGINT      NOT NULL COMMENT '商品 id',
    order_id     BIGINT      NOT NULL COMMENT '兑换单 id——评价资格的凭据，非本人已领取的单不得评',
    volunteer_id BIGINT      NOT NULL COMMENT '评价人 volunteer.id',
    rating       TINYINT     NOT NULL COMMENT '评分 1~5',
    content      VARCHAR(512)         DEFAULT NULL COMMENT '评价内容',
    status       TINYINT     NOT NULL DEFAULT 1 COMMENT '1正常/0已下架（后台可下架不当言论）',
    create_time  DATETIME    NOT NULL COMMENT '创建时间',
    update_time  DATETIME             DEFAULT NULL COMMENT '更新时间',
    is_deleted   TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order (order_id),
    KEY idx_goods_id (goods_id, id),
    KEY idx_volunteer (volunteer_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分商品评价（Row 8）';

-- 权限点：本批 5 个。命名前缀按**模块**（donate:），与既有 activity:/honor:/org:/pub:/user:/data:
-- 一致；表名才按聚合根概念分 mall_/donate_。改这里必须同步 OrganizationRbacTest.permissionsSeeded
-- 的总数断言（51 → 56），那条断言就是防漏改的哨兵。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('donate:goods',       '积分商品管理',   'donate', 2, 61, NOW(), 0),
('donate:goods-audit', '积分商品审核',   'donate', 3, 62, NOW(), 0),
('donate:order',       '兑换单查看',     'donate', 1, 63, NOW(), 0),
('donate:order-audit', '兑换审核',       'donate', 3, 64, NOW(), 0),
('donate:verify',      '取货码现场核销', 'donate', 2, 65, NOW(), 0);
