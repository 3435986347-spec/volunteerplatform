-- V101 组织架构补种「理事会」节点（协会 2026-08-11 追认「理事会在组织架构里面也有，是组织架构」，回执二问题 C；知识库协会决策 XH04）。
--
-- 【为什么现在补】V5 只种了协会 + 组织部 / 秘书部 / 宣传部，从没种过理事会——原型 P19 的部门下拉本来就列着它，
--   而协会 2026-08-11 / 09-16 起理事会是奖惩终审、申诉受理、账号恢复与全部限制权限的主体，架构树里却放不进它的成员。
-- 【挂在哪】协会根节点下（parent_id 为空且未删的那一行，取 id 最小的；V5 种的就是它），与三个部门平级；
--   sort 5 排在组织部（10）之前，与原型 P19 的顺序一致：理事会、组织部、秘书部、宣传部。
-- 【不打扰已有数据】V69 起超管可以在后台增删节点：根节点下已经有未删的「理事会」（有人先在后台建了）就什么也不插；
--   没有未删的根节点也不插，免得凭空造出第二棵树。已删的「理事会」不算数。
-- 【本文件形态】一条 INSERT … SELECT（同表先查后插，MySQL 会先物化 SELECT 的结果）。
INSERT INTO organization_structure_node (parent_id, name, title, sort, create_time, is_deleted)
SELECT root.id, '理事会', '奖惩终审、申诉受理与账号恢复', 5, NOW(), 0
FROM (SELECT id FROM organization_structure_node
      WHERE parent_id IS NULL AND is_deleted = 0
      ORDER BY id LIMIT 1) root
WHERE NOT EXISTS (SELECT 1 FROM organization_structure_node n
                  WHERE n.parent_id = root.id AND n.name = '理事会' AND n.is_deleted = 0);
