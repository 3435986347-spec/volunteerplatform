-- V88 志愿者：注销冷静期 / 后台恢复账号 / 每年确认学校年级。
--
-- 【注销】协会 2026-09-16：注销＝退出协会；建议 7 天冷静期；注销后手机号释放（别人可以用这个号注册）、
--         身份证不能再注册；本人想回来时由理事会在后台恢复账号。
--         冷静期内账号照常可用、本人可随时撤销；到期即注销——「到没到期」按时间现算（与处置到期同一条纪律，
--         不靠定时任务改状态位），到期后第一次碰到这个账号（请求闸门 / 登录 / 按手机号查找）时当场落定：
--         status=2、清手机号与密码、合成 openid 换成 deactivated:{id}（否则同号重新注册会撞 openid 唯一键）。
--         身份证哈希不动——uk_id_card_hash 正是「身份证不能再注册」的落点。
-- 【恢复】理事会（user:restore，见 V90）恢复时填一个当前没被占用的手机号（号释放之后可能已被别人用了）。
-- 【每年确认学校年级】协会 2026-09-16：每年由志愿者确认学校和年级即可，审核自动通过，原则上不影响报名。
--         记「已确认到哪个学年」（9 月 1 日为界，与 grade_upgrade_year 同口径），小于当前学年就提示他确认一次。
ALTER TABLE volunteer
    ADD COLUMN deactivate_request_time   DATETIME DEFAULT NULL COMMENT '申请注销时间（冷静期内可撤销，撤销即清空）',
    ADD COLUMN deactivate_effective_time DATETIME DEFAULT NULL COMMENT '注销生效时间＝申请时间 + 冷静期；到期按时间现算，第一次碰到时落定为 status=2',
    ADD COLUMN deactivated_time          DATETIME DEFAULT NULL COMMENT '注销落定时间（清手机号、换 openid 的那一刻）',
    ADD COLUMN restore_by                BIGINT   DEFAULT NULL COMMENT '恢复账号的后台账号 admin_user.id（理事会）',
    ADD COLUMN restore_time              DATETIME DEFAULT NULL COMMENT '恢复账号时间',
    ADD COLUMN school_confirm_year       INT      DEFAULT NULL COMMENT '学校年级已确认到的学年（9 月 1 日为界；小于当前学年即提示本人确认）';
