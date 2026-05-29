-- V13 volunteer.manager_flag 加操作审计：记录「谁、何时」改的管理团队标记
-- V11 只建了 manager_flag 值本身，没有操作人/时间；管理团队标记影响积分倍率，属敏感人事动作，需可追溯。
-- manager_flag_by 记 admin_user.id（手动开关由后台账号触发；将来「报名管理团队」审批回写时同样落审批人）。
-- 通用 update_time（BaseEntity）只反映「最后一次任何更新」，故另立专列记本标记的操作人与操作时间。

ALTER TABLE volunteer
    ADD COLUMN manager_flag_by   BIGINT   DEFAULT NULL COMMENT '管理团队标记操作人 admin_user.id',
    ADD COLUMN manager_flag_time DATETIME DEFAULT NULL COMMENT '管理团队标记最近操作时间';
