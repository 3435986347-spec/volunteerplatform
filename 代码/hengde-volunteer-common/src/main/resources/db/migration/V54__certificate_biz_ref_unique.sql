-- 证书业务来源键的幂等约束（V3 结对批）。一条 ALTER：加生成列 + 加唯一键，同生共死。
--
-- 【为什么生成列里不排除软删行】与 `uk_slot_cert` 保持同一口径：证书软删**仍占键**，
-- 于是重复触发时会命中那一行并「恢复原记录、保留原编号与文件」（V31 定下的口径 b），
-- 而不是绕过软删另发一张新编号的证书。
-- ——注意这与**证书样本** `uk_template_active_scope` 的取舍相反（那里软删就该释放，否则界面死锁）：
-- 样本是可替换的配置，证书是发出去的凭据，两者要的东西不一样。
--
-- 【键里带上 type】不同类型的证书各自独立编号空间，日后再有别的来源（如 i志愿导出）不会与捐赠证书撞键。

ALTER TABLE honor_certificate
    ADD COLUMN active_biz_ref VARCHAR(80) GENERATED ALWAYS AS (
        CASE WHEN biz_ref IS NULL THEN NULL ELSE CONCAT(type, ':', biz_ref) END) STORED
        COMMENT '「类型 + 业务来源键」，仅 biz_ref 非空时取值；软删行仍占用（与 uk_slot_cert 同口径）',
    ADD UNIQUE KEY uk_cert_biz_ref (active_biz_ref);
