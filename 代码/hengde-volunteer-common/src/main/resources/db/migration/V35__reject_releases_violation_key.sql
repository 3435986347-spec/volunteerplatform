-- 被驳回的处罚单不再永久占住「一条违规最多一张单」的位置（V2 第 5 批评审第 6 轮）。
--
-- 【缺陷】V32 的 uk_active_violation 只按 is_deleted 释放占位：
--     active_violation_id = CASE WHEN is_deleted = 0 THEN violation_id ELSE NULL END
--   于是一张【被驳回】的单照样占着那条违规。而系统里没有任何「修改 / 删除 / 重新提交」入口——
--   开单人填错了类别或说明、组织部驳回并写明原因之后，那条违规就再也开不出第二张单了。
--   实测（mysql:8.0.16）：驳回行存在时再插一张同违规的单 → ERROR 1062 Duplicate entry。
--   而 reject() 强制要求填写驳回原因，其用意本就是让开单人据此改正——改正却无路可走。
--
-- 【修法】把「已驳回」也排除在占位之外：一条违规同时最多只有一张【未被驳回】的单。
--   驳回后可以重新开单，重开的单仍要走组织部审核，「一条违规最多转出一张生效处罚」不变。
--
--   ⚠️ 这是**推论，不是需求原文**：Row 41 F 只写了「均需组织部同学审核才可显示」，
--   没说驳回之后还能不能重开。取这个口径的理由是另一个选项没有任何出口——
--   「驳回 = 该违规永久不可再处理」会把一次填写失误变成不可恢复的状态。
--   已记入《协会待确认清单》第 9 条，协会若认为驳回即终局，改回本表达式即可。
--
-- 【为什么可以单条 ALTER 改生成列表达式】MySQL 8 的 MODIFY COLUMN 支持直接换表达式并重建
--   依赖它的唯一键（已在容器实测：改完 uk_active_violation 仍在、行为按新表达式生效）。
--   若拆成 DROP KEY / DROP COLUMN / ADD COLUMN / ADD KEY 四条，就又回到 V33 抬头那个
--   「做了一半、无法重跑」的形态。**一条语句 = 要么全成、要么什么都没发生。**
--
-- 【为什么不会因存量数据失败】新表达式只会把更多行映射成 NULL（多个 NULL 互不相同），
--   等价类只拆不合，原本能共存的行改完仍然互不冲突。
ALTER TABLE honor_reward_punish
    MODIFY COLUMN active_violation_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 AND review_status <> 2 THEN violation_id ELSE NULL END) STORED
        COMMENT '占位键：仅「未删除且未被驳回」的单占住其来源违规，见 V35';
