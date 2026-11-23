-- 志愿者昵称：用户可在「我的资料」自助修改，且全局唯一（去重）。
-- 新增列默认 NULL，存量行均为 NULL；MySQL InnoDB 唯一索引允许多个 NULL，故不影响存量数据，
-- 仅非空昵称参与唯一性约束。服务层将空白昵称统一落 NULL，避免空串相互冲突。
ALTER TABLE volunteer ADD COLUMN nick_name VARCHAR(50) DEFAULT NULL COMMENT '昵称（用户可改，全局唯一）';
ALTER TABLE volunteer ADD UNIQUE KEY uk_nick_name (nick_name);
