-- 勋章「最后一次通过审核的样式」快照（V2 第 3 批补丁）。
--
-- ── 修的是什么 ──
-- V28 只有一份「当前定义」。改一枚已启用的勋章时（MedalService.update）是**原地覆盖**
-- name / icon_url / description / condition_*，同时把 status 退回待审核。
-- 但志愿者端 MedalGrantService.myMedals 是「已启用定义 ∪ 本人已获得的定义」——
-- 退回待审核后 listEnabled() 不再返回它，unionOwned() 却按 medal_id 把同一行补了回来。
-- 于是：
--   1. 已经拿到这枚勋章的志愿者，**立刻**看到管理员刚改、尚未过审的名称与图标；
--   2. 这一版即使随后被驳回，驳回稿仍会一直挂在他的「我的勋章」里，直到下一次过审。
-- 「样式必须审核」这条需求在已获得者身上等于失效。V28 只快照了 reward_points（在发放记录上），
-- 没有任何地方留下「上一次审核通过的样子」。
--
-- ── 为什么快照放在 honor_medal 而不是 honor_medal_grant ──
-- 放发放记录上（与 reward_points 同款）会让每个人永远停在授予当时的样式：
-- 之后修好的错别字、换清晰的图标都传不到老用户，同一枚勋章在不同人那里长期显示不一致。
-- 放定义行上则是「志愿者一律看已过审版本」：审核中的改动完全不外泄，一旦过审所有人同步更新。
-- 这与既有语义也一致——停用不收回已发的勋章（MedalStatus 与接口文档都是这么写的），
-- 停用行的快照仍然有效，已获得者继续看到它最后过审的样子。
--
-- ── 为什么不含 sort ──
-- sort 走 MedalService.updateSort，那条路**刻意不触碰审核状态**（纯展示性排序不该重审）。
-- 既然它本来就不需要审核，就不存在「未过审的 sort」，快照它反而会让排序改完不生效。
--
-- 幂等：Flyway 单向执行，此处不写 IF NOT EXISTS（与 V26/V27/V28 一致）。

ALTER TABLE honor_medal
    ADD COLUMN approved_name                VARCHAR(64)  DEFAULT NULL COMMENT '最后一次过审的名称；志愿者端只读这一列' AFTER name,
    ADD COLUMN approved_icon_url            VARCHAR(512) DEFAULT NULL COMMENT '最后一次过审的图标 URL' AFTER icon_url,
    ADD COLUMN approved_description         VARCHAR(512) DEFAULT NULL COMMENT '最后一次过审的说明' AFTER description,
    ADD COLUMN approved_condition_type      TINYINT      DEFAULT NULL COMMENT '最后一次过审的获取条件' AFTER condition_type,
    ADD COLUMN approved_condition_threshold BIGINT       DEFAULT NULL COMMENT '最后一次过审的条件阈值' AFTER condition_threshold,
    ADD COLUMN approved_reward_points       INT          DEFAULT NULL COMMENT '最后一次过审的附带积分' AFTER reward_points;

-- ── 存量回填 ──
-- 已启用(2)/已停用(4) 这两个状态**必然经过审核**，当前值就是过审值，直接回填。
UPDATE honor_medal
SET approved_name                = name,
    approved_icon_url            = icon_url,
    approved_description         = description,
    approved_condition_type      = condition_type,
    approved_condition_threshold = condition_threshold,
    approved_reward_points       = reward_points
WHERE status IN (2, 4);

-- 草稿(0)/待审核(1)/已驳回(3) 里也可能藏着「曾经过审、被改后退回」的行——正是本次要修的场景。
-- 它们的过审版本在 V28 下已被覆盖、无法还原，只能退而求其次用当前值回填，
-- 且**只回填确实发出去过的**（有生效发放记录），避免给从未过审的草稿凭空造出一个「过审版本」。
-- 这批行的快照因此可能仍是未过审内容——这是 V28 期间已经发生的既成事实，本迁移无法修复历史，
-- 只能保证从此以后不再产生。上线后建议人工复核这批勋章的样式。
UPDATE honor_medal m
SET m.approved_name                = m.name,
    m.approved_icon_url            = m.icon_url,
    m.approved_description         = m.description,
    m.approved_condition_type      = m.condition_type,
    m.approved_condition_threshold = m.condition_threshold,
    m.approved_reward_points       = m.reward_points
WHERE m.approved_name IS NULL
  AND EXISTS (SELECT 1
              FROM honor_medal_grant g
              WHERE g.medal_id = m.id
                AND g.status = 1
                AND g.is_deleted = 0);
