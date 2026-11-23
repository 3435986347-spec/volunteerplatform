-- V9 「一人一组」DB 层终极兜底
-- 背景：create()/join() 已加 Redisson 锁串行化，但 importGroups（管理员批量导入）及未来任何
--   新写入口都可能绕过应用层「一人一组」校验，造成同一志愿者出现在多个 active/pending 小组。
-- 方案：在 volunteer_group_member 上加一个 VIRTUAL 生成列——仅当成员处于 PENDING(0)/ACTIVE(1)
--   且未逻辑删除时取 volunteer_id，否则为 NULL——再对其建唯一键。
--   MySQL 唯一索引允许多个 NULL，故 REJECTED(2)/LEFT(3)/REMOVED(4)/已删 的历史行可共存，
--   只有「活跃归属」最多一条。VIRTUAL 列不落盘，按行即时计算。
-- 效果：任何入口插入第二条活跃成员行都会被 MySQL 以 Duplicate entry 拒绝（应用层再翻译为业务异常）。

ALTER TABLE volunteer_group_member
    ADD COLUMN active_volunteer_lock BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN status IN (0, 1) AND is_deleted = 0 THEN volunteer_id END
        ) VIRTUAL COMMENT '一人一组唯一约束载体：活跃(0/1)且未删时=volunteer_id，否则NULL',
    ADD UNIQUE KEY uk_active_volunteer (active_volunteer_lock);
