-- 结对项目加受助人基本情况（协会 2026-10-16 答复五 丙-2 选 B：按原型 P47 / P48 / P102 单独一块展示）。
--
-- 与微心愿（V51）同一处理：姓名、学校是未成年人的 PII，**密文**存储；志愿者端对外打 *，
-- 只有这个项目上「已成立」结对的那位结对人与后台看得到全文——判定在服务端，前端不显示代替不了服务端不下发。
-- 上报单位与微心愿一样取受赠单位主数据并快照名称。全部可空：改造前的项目没有这些信息，照常显示「详细资料」那段文字。
-- 一条 ALTER，同生共死。

ALTER TABLE donate_pair_project
    ADD COLUMN beneficiary_name   VARCHAR(255) DEFAULT NULL COMMENT '受助人姓名（**密文**）' AFTER detail,
    ADD COLUMN beneficiary_gender TINYINT      DEFAULT NULL COMMENT '性别 1男/2女' AFTER beneficiary_name,
    ADD COLUMN beneficiary_age    TINYINT      DEFAULT NULL COMMENT '年龄' AFTER beneficiary_gender,
    ADD COLUMN beneficiary_school VARCHAR(512) DEFAULT NULL COMMENT '学校（**密文**）' AFTER beneficiary_age,
    ADD COLUMN beneficiary_grade  VARCHAR(32)  DEFAULT NULL COMMENT '年级' AFTER beneficiary_school,
    ADD COLUMN report_org_id      BIGINT       DEFAULT NULL COMMENT '上报单位（donate_recipient_org）' AFTER beneficiary_grade,
    ADD COLUMN report_org_name    VARCHAR(128) DEFAULT NULL COMMENT '【保存时快照】上报单位名' AFTER report_org_id;
