-- 结对项目的「已到账」（V3 捐款批）。
--
-- 【两个数并存，含义不同】pledged_amount 是认捐额（协会确认结对成立时累加，结对批就有）；
--   raised_amount 是真正到账的钱（捐款成功时累加、退款时减回）。Row 10「已筹多少钱」自本批起展示后者——
--   把认捐额当「已筹」给捐赠人看，是会被追责的失真（结对批文件头已说明）。
--
-- 【本文件形态】一条 ALTER。

ALTER TABLE donate_pair_project
    ADD COLUMN raised_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '已到账金额（捐款成功累加、退款减回）' AFTER pledged_amount;
