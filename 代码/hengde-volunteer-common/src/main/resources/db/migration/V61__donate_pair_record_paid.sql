-- 结对登记的「已付」（V3 捐款批）。
--
-- 【结对捐款付的是「认捐额 − 已付」】认捐额在登记时定（指定金额 / 全款），付款不再让人重新输入金额；
--   这一列记这一条结对到账了多少，付清之后不再能发起结对捐款。取消已付款的结对时按它原路退款。
--
-- 【本文件形态】一条 ALTER。

ALTER TABLE donate_pair_record
    ADD COLUMN paid_amount DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT '已到账金额（结对捐款成功累加、退款减回）' AFTER amount;
