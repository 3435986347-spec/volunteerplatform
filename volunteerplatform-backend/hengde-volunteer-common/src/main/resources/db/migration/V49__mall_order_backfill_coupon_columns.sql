-- 回填 V48 新增的两列（V3 卷批）。
--
-- 【为什么与 V48 分开】ALTER 隐式提交后若 UPDATE 失败，重跑会在 ALTER 上撞「列已存在」——
--   同 V43（加列）/ V44（归类存量）的拆法。
--
-- 【回填口径】
--   original_points = points：存量单都没用过卷，标价等于实际扣分。
--   pickup_operator_type = 1：卷批之前只有管理员能核销，已核销的存量单一律是管理员。
--   未核销的单 pickup_operator 为 NULL，类型也保持 NULL——不给一个没发生的动作编类型。
-- 【只动 original_points 为 NULL 的行】让本语句重跑无害（理论上 Flyway 不会重跑成功过的版本，
--   但手工修库时有人照着再执行一遍，结果也不变）。

UPDATE mall_order
   SET original_points      = points,
       pickup_operator_type = CASE WHEN pickup_operator IS NOT NULL THEN 1 ELSE NULL END
 WHERE original_points IS NULL;
