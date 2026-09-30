-- V93 注销冷静期到期扫描的索引。
--
-- 【为什么】DeactivationFinalizeJob 每 10 分钟查「deactivate_effective_time 已到、还没落定」的账号，
--         V88 加列时没配索引，每一轮都是 volunteer 全表扫描——代价随志愿者总数、而不是随待落定的那几个人增长。
--         绝大多数行这一列是 NULL，索引只收录申请过注销的那几行，范围查询 <= NOW() 直接走它。
--         VolunteerQueryService 里「排除已过冷静期」的条件（IS NULL OR > NOW()）是和主键 / 手机号哈希一起用的，不依赖它。
ALTER TABLE volunteer ADD INDEX idx_deactivate_due (deactivate_effective_time);
