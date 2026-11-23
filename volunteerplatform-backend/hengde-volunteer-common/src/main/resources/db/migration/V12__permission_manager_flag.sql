-- V12 organization 域新增权限点：志愿者「管理团队」标记的后台手动开关（org:manager-flag）
-- V11 加了 volunteer.manager_flag 字段（管理团队积分倍率 ×1.2 的数据通道），本期补一个手动开关接口
--   PUT /a/organization/volunteers/{id}/manager-flag，由组织部直接置位/取消标记。
-- 「报名管理团队」问卷审批（reserved，回写本标记）将来共用本权限点或另设——届时再定。
-- type=2 功能级（与 V2 org:* 一致）；接 V2 organization 组的 sort=34 之后，取 35。超管走 * 通配不受影响。

INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('org:manager-flag', '管理团队标记', 'org', 2, 35, NOW(), 0);
