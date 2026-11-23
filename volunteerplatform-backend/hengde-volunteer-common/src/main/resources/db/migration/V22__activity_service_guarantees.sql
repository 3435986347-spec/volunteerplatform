-- 活动「服务保障」：发布时不定项选择（志愿者服装/提供饮水/…/其他，共 12 项），
-- 以逗号分隔的 key 存储（如 "clothing,water,insurance"），key 与后端 ServiceGuarantee 常量、
-- 小程序 utils/service-guarantees.js、详情页图标资产一一对应。详情页据此把对应图标由灰变红；
-- 未选/留空则全灰。新增列默认 NULL，不影响存量活动。
ALTER TABLE activity ADD COLUMN service_guarantees VARCHAR(255) DEFAULT NULL COMMENT '服务保障项(逗号分隔key，对齐 ServiceGuarantee 常量)';
