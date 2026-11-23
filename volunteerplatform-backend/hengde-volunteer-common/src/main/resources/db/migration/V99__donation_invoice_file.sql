-- 捐款发票文件（协会 2026-10-16 答复五 丁-5：提交时记抬头；财务把开好的发票文件传上来；用户可以下载；订单显示已完结）。
--
-- 发票文件放**私有**对象存储（里面有抬头与税号），志愿者只拿短期签名地址，与电子证书同一做法。
-- 一条 ALTER，同生共死。

ALTER TABLE donate_donation
    ADD COLUMN invoice_file_key  VARCHAR(512) DEFAULT NULL COMMENT '发票文件（私有对象存储 key）' AFTER invoice_by,
    ADD COLUMN invoice_file_name VARCHAR(255) DEFAULT NULL COMMENT '发票文件原名（下载时用）' AFTER invoice_file_key;
