-- 积分兑换规则（V3 商城批收尾）：Row 8 C「兑换规则（文字 + 图片）」。
--
-- 【为什么是 V42 而不是 V41】与 V41 同一个理由：V2 第 5 批先合入 main、占了 V40，
--   商城这两个各顺延一位。详见 V41 文件头。
--
-- 【为什么是一张单行表，而不是配置项】自提点走配置，是因为它是**运营常量**、且需要下单快照
--   （历史单据要还原出当时的领取点）。兑换规则是**内容**：协会会改、会随活动调整。
--   放配置项意味着改一句文案要重启服务，两者形似而性质相反。
--
-- 【为什么不复用 publicity 的公告】/v/publicity/announcements 会把它列进公告流；
--   要排除就得给公告加一个标记列——**同样要迁移**，还把商城概念漏进 publicity，
--   而且有人删掉那条公告会让商城页面静默变空。省不下迁移，却买来一处跨域耦合。
--
-- 【刻意不做的：上下架 / 多版本 / 生效时间】Row 8 只要「一份当前规则」。
--   加状态位是替协会做决定——与「原型已经答过的别写成待决策」是同一条纪律的反面：
--   **需求没提的别自己发明**。真要历史版本，那时再加一张 _history 表，本表不用改。
--
-- 【固定 id=1】单行表，读写都打这一行。迁移里就把它建出来，
--   于是读路径永远查得到、写路径是一条 UPDATE——不必「先查再插」（并发下必漏），
--   也不必 upsert。写入侧另用 INSERT ... ON DUPLICATE KEY UPDATE 兜底，
--   即便这行被人手工删掉也能自愈。
--
-- 【images 用换行分隔而非逗号】URL 里可以合法地出现逗号，**不可能出现换行**。
--   项目已有的 service_guarantees 用逗号是因为那存的是固定 key，不是 URL。
--   不引 JSON：fastjson2 只在父 POM 的 dependencyManagement 里声明过，全项目零引用，
--   为一列图片地址新拉一个依赖不值得。
--
-- 【不新增权限点】读写复用 donate:goods（权限点仍 56 个，V3 全部落地后为 63）。
--   拆权限要有「谁能改 A 但不能改 B」的现实需求，这里没有——user:export 那次拆
--   是因为导出的数据敏感度不同，不是同一回事。
--
-- 【本文件没有版本相关语句】一条 CREATE TABLE + 一条 INSERT，
--   「一个迁移文件只放一条有失败可能的语句」那条针对的是 V33/V34 那种版本相关特性
--   （CHECK 要 8.0.16+、utf8mb4_0900_bin 要 8.0.17+），本文件不涉及。

CREATE TABLE mall_exchange_rule (
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键；单行表，固定为 1',
    content     TEXT              DEFAULT NULL COMMENT '规则正文（富文本）',
    images      TEXT              DEFAULT NULL COMMENT '配图 URL，**换行分隔**（URL 可含逗号、不可含换行）',
    update_by   BIGINT            DEFAULT NULL COMMENT '最后修改人 admin_user.id',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME          DEFAULT NULL COMMENT '更新时间',
    is_deleted  TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除；本表不提供删除入口，留列只为与 BaseEntity 对齐',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分兑换规则（Row 8 C，单行）';

INSERT INTO mall_exchange_rule (id, content, images, create_time, update_time, is_deleted)
VALUES (1, NULL, NULL, NOW(), NOW(), 0);
