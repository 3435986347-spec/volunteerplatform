-- 爱心企业批·商品段：企业被暂停 / 删除时，它赞助的积分商品对志愿者不可见、也兑换不了（Row 15 F「暂停等相关权限」）。
--
-- 【为什么是一列而不是把商品下架或隐藏】暂停可以恢复：若暂停时把商品改成隐藏，恢复时分不清哪些是企业自己本来就隐藏的；
--   另起一列只表达「赞助方当前不可用」，由 enterprise 模块在暂停 / 恢复 / 删除企业的同一事务里整批置位。
-- 【承重点】下单扣库存那条 UPDATE ... JOIN（MallGoodsSpecMapper.deductStock）同时带上 g.sponsor_suspended = 0——只在列表里藏起来挡不住直接拿规格 id 下单。
-- 【本文件形态】单条 ALTER（两个子句同属一条语句，原子），新库上没有失败的可能。

ALTER TABLE mall_goods
    ADD COLUMN sponsor_suspended TINYINT NOT NULL DEFAULT 0 COMMENT '赞助企业当前不可用（暂停 / 删除）：1=志愿者不可见、不可兑换' AFTER sponsor_name,
    ADD KEY idx_sponsor (sponsor_enterprise_id, status);
