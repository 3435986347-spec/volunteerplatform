-- 报名管理团队接入问卷引擎（V4 问卷引擎批，Row 46「这个板块需预留，类似于一个问卷调查」）。
--
-- 【不推翻 V23】固定的三项（申请理由 / 相关经历 / 期望部门）照旧必填，后台审核页原样可用；
--   协会在后台给「报名管理团队」场景发布了问卷时，申请要连同答卷一起提交，答卷 id 记在这里。没有问卷时本列为空。
--
-- 【本文件形态】一条 ALTER（与 V63 分开：ALTER 在存量表上，失败可能与新建表不同，按「一个文件一条有失败可能的语句」拆开）。

ALTER TABLE manager_application
    ADD COLUMN form_submission_id BIGINT DEFAULT NULL COMMENT '随申请提交的问卷答卷 org_form_submission.id（该场景没有问卷时为空）' AFTER expect_department;
