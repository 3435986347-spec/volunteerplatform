-- 志愿者表补三列（V4 个人中心补全批）。
--
-- 【手写签名板】Row 48「手写签名板：可以理解为涂鸦」。另起一列，不复用注册时的 signature_url——
--   那一张是签协议时的留痕（V17 记着签的是哪一版协议），在安全中心里随手改掉等于把协议凭证换了。
--
-- 【年级每年 9 月升一级】Row 25「年级每年9月份增加一级；六年级、九年级、高三、大三、大四、大五结束后的9月份会提示他们修改学校和年级；
--   选择已毕业的，则不需要后续提示」。
--   grade_upgrade_year：这个人的年级「已经对应到」哪个学年（学年以 9 月 1 日为界，2026-09 起是 2026 学年）。
--     升级任务只处理小于当前学年的行，再跑一遍什么也不改——幂等靠它，不靠「每年只在 9 月 1 日跑一次」
--     （那天服务不在线，这一年就永久漏升）。本人或后台改年级时置为当前学年。
--   grade_prompt_pending：到了分界年级，任务不替他升级，而是挂上「请修改学校和年级」的提示；本人改过年级即清除。
--
-- 【本文件形态】一条 ALTER；存量回填在 V68（ALTER 隐式提交后回填再失败，重跑会撞「列已存在」）。

ALTER TABLE volunteer
    ADD COLUMN pad_signature_url    VARCHAR(512) DEFAULT NULL COMMENT '安全中心手写签名板（与注册协议签名分开）' AFTER signature_url,
    ADD COLUMN grade_upgrade_year   INT          DEFAULT NULL COMMENT '年级已对应到的学年（9 月 1 日为界）' AFTER grade,
    ADD COLUMN grade_prompt_pending TINYINT      NOT NULL DEFAULT 0 COMMENT '是否挂着「请修改学校和年级」提示' AFTER grade_upgrade_year;
