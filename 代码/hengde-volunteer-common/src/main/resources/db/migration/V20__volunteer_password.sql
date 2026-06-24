-- V20 auth 域：志愿者「手机号 + 密码」登录体系
-- 志愿者原为微信/手机号验证码无密码登录；本列存 BCrypt 密文，供「设置密码后用手机号+密码登录」。
-- 账号 = 手机号（按 V1 已有的 UNIQUE KEY uk_phone_hash 保证一手机号一账号，此处不重复建索引）。

ALTER TABLE volunteer
    ADD COLUMN password VARCHAR(100) DEFAULT NULL COMMENT '登录密码（BCrypt 密文），手机号+密码登录用；NULL=未设密码';
