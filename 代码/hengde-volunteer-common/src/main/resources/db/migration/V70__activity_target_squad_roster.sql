-- 活动补全（V4 活动补全批）：指定分队报名 + 名单公示。
--
-- 【指定分队报名】V3 起 activity 上有 enroll_scope（0 全平台 / 1 指定分队）与【临时】列 target_squad_ids（逗号分隔、从没校验过，
--   ActivityService 一直直接拒绝 enroll_scope=1）。协会口径是「指定单个分队」，故另起单值列 target_squad_id；
--   V3 那一列不改不删（迁移跑过就不能原地改），自本批起不再读写。存量没有 enroll_scope=1 的行（一直被拒），不需回填。
--
-- 【名单公示】xlsx Row 13「公示显示时间为组织部确认名单到活动开始，活动开始后则不再显示」：
--   roster_publish_time 为空＝还没确认名单、不公示；「活动开始」按 start_time 与 run_status 现算，不落库。
--
-- 【本文件形态】一条 ALTER TABLE（MySQL 的多列 ADD 是一条语句，失败即整条不生效）。

ALTER TABLE activity
    ADD COLUMN target_squad_id     BIGINT   DEFAULT NULL COMMENT '指定分队 volunteer_squad.id（enroll_scope=1 时必填；取代 V3 的 target_squad_ids）' AFTER target_squad_ids,
    ADD COLUMN roster_publish_time DATETIME DEFAULT NULL COMMENT '名单公示开始时间（组织部确认名单；为空＝不公示）',
    ADD COLUMN roster_publish_by   BIGINT   DEFAULT NULL COMMENT '确认名单的管理员 admin_user.id';
