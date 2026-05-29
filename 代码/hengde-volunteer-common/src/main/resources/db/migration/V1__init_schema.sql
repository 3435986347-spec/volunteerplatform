-- V1 初始 Schema（全项目唯一的迁移序列，集中放在 common，api 运行期与各领域测试共用）
-- Flyway 按版本号升序执行，文件名规则：V{major}__{description}.sql
-- 各领域开发时在此追加建表，或新建 V2__xxx.sql；版本号全局唯一，避免多领域撞车。
-- 公共列约定（对应 com.hengde.common.entity.BaseEntity）：
--   id          BIGINT 自增主键
--   create_time DATETIME 创建时间（MyBatis-Plus 自动填充）
--   update_time DATETIME 更新时间（自动填充）
--   is_deleted  TINYINT 逻辑删除 0=未删除 1=已删除

-- ============================================================
-- auth 领域
-- ============================================================

-- 志愿者（C 端用户）。微信登录即建行（游客态），实名注册后 register_time 非空。
CREATE TABLE volunteer (
    id                       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    openid                   VARCHAR(64)  NOT NULL COMMENT '微信小程序 openid',
    unionid                  VARCHAR(64)  DEFAULT NULL COMMENT '微信 unionid（多端）',
    real_name                VARCHAR(32)  DEFAULT NULL COMMENT '姓名',
    id_card_no               VARCHAR(128) DEFAULT NULL COMMENT '身份证号（AES-GCM 密文）',
    id_card_hash             CHAR(64)     DEFAULT NULL COMMENT '身份证 HMAC（查重/判断已注册）',
    phone                    VARCHAR(64)  DEFAULT NULL COMMENT '手机号（AES-GCM 密文）',
    phone_hash               CHAR(64)     DEFAULT NULL COMMENT '手机号 HMAC（精确搜/换绑查重）',
    gender                   TINYINT      DEFAULT NULL COMMENT '性别 0未知/1男/2女',
    birthday                 DATE         DEFAULT NULL COMMENT '生日（身份证解析）',
    political_status         TINYINT      DEFAULT NULL COMMENT '政治面貌 1群众/2共青团员/3中共预备党员/4中共党员/5民主党派',
    school                   VARCHAR(64)  DEFAULT NULL COMMENT '学校',
    grade                    INT          DEFAULT NULL COMMENT '年级有序编码 1~9年级/10-12高中/13-17大学/18毕业',
    address                  VARCHAR(255) DEFAULT NULL COMMENT '通讯地址',
    i_volunteer_code_url     VARCHAR(512) DEFAULT NULL COMMENT 'i志愿者码图片（用户自传）',
    avatar_url               VARCHAR(512) DEFAULT NULL COMMENT '头像',
    emergency_contact_name   VARCHAR(32)  DEFAULT NULL COMMENT '紧急联系人姓名',
    emergency_contact_phone  VARCHAR(64)  DEFAULT NULL COMMENT '紧急联系人电话（AES-GCM 密文）',
    signature_url            VARCHAR(512) DEFAULT NULL COMMENT '协议手写签名图片',
    position                 VARCHAR(64)  DEFAULT NULL COMMENT '职位（后台设置，前端名字下展示）',
    squad_id                 BIGINT       DEFAULT NULL COMMENT '所属分队（organization 域）',
    status                   TINYINT      NOT NULL DEFAULT 0 COMMENT '账号状态 0正常/1禁用/2注销',
    register_time            DATETIME     DEFAULT NULL COMMENT '实名注册时间；NULL=游客，非空=已实名志愿者',
    membership_expire_date   DATE         DEFAULT NULL COMMENT '会员费到期日（预留占位）',
    create_time              DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_time              DATETIME     DEFAULT NULL COMMENT '更新时间',
    is_deleted               TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_openid (openid),
    UNIQUE KEY uk_id_card_hash (id_card_hash),
    UNIQUE KEY uk_phone_hash (phone_hash),
    KEY idx_squad_id (squad_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '志愿者（C端用户）';

-- 后台账号（管理团队）。细粒度权限（菜单/数据/审核）由 organization 域的 RBAC 表挂接。
CREATE TABLE admin_user (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username         VARCHAR(64)  NOT NULL COMMENT '登录账号',
    password         VARCHAR(100) NOT NULL COMMENT '密码（BCrypt 密文）',
    real_name        VARCHAR(32)  DEFAULT NULL COMMENT '姓名',
    phone            VARCHAR(20)  DEFAULT NULL COMMENT '手机号（明文，找回密码用）',
    department       VARCHAR(32)  DEFAULT NULL COMMENT '部门',
    is_super_admin   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否超管 1是/0否',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '状态 0启用/1禁用',
    last_login_time  DATETIME     DEFAULT NULL COMMENT '上次登录时间',
    create_time      DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_time      DATETIME     DEFAULT NULL COMMENT '更新时间',
    is_deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '后台账号（管理团队）';
