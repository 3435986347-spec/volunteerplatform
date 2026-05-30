-- V17 auth 域：志愿者注册记录所签协议版本（V1.1 协议阅读+手写签名小批）
-- 手写签名图 URL 已在 V1 的 volunteer.signature_url 落地、注册必填；本列补「入库标记」——
-- 记录注册时所签署的协议版本，便于协议改版后追溯谁签的哪一版（合规留痕）。
-- 签名图本身是签署凭据；版本由服务端当前配置 hengde.auth.agreement-version 在注册时写入。

ALTER TABLE volunteer
    ADD COLUMN signed_agreement_version VARCHAR(32) DEFAULT NULL COMMENT '注册时所签志愿者协议版本';
