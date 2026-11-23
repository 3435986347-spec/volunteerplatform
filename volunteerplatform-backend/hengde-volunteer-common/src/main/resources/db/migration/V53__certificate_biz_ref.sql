-- 证书补「业务来源键」列（V3 结对批：捐赠证书）。
--
-- 【为什么 uk_slot_cert 挡不住重复的捐赠证书】它是 `(type, volunteer_id, slot_id)`，
-- 而捐赠证书没有场次、`slot_id` 为 NULL——MySQL 唯一索引**视多个 NULL 互不相同**，
-- 同一笔结对重复触发会插出任意多张证书，而它们各有编号、各自看着都像正式证书。
-- 故另设一个显式的业务来源键：`biz_ref`，形如 `pair:{结对登记 id}`。
--
-- 【这一版只加列】唯一键建在生成列上，放在 V54。两条 ALTER 分开是因为 DDL 各自隐式提交：
-- 合在一个文件里，第二条失败会让库停在「列加好了、键没建」又无法重跑的状态（第二次重跑第一条撞「列已存在」）。
--
-- 【既有的 CHECK 不受影响】`ck_cert_activity_scope` 写的是 `type <> 1 OR (...)`，
-- 只约束活动证书；捐赠证书 type = 3、activity_id 与 slot_id 皆为 NULL，天然通过。

ALTER TABLE honor_certificate
    ADD COLUMN biz_ref VARCHAR(64) DEFAULT NULL
        COMMENT '业务来源键，形如 pair:{结对登记id}；活动证书为 NULL（它由 uk_slot_cert 保幂等）';
