-- 爱心企业账号（V4 爱心企业批·账号段）：xlsx Row 15「提供渠道给商家注册，需要收集商家的头像，企业名称，企业信用代码，企业介绍，项目负责人名字，电话（验证码验证），
--   申请账号、密码，然后有个渠道给商家进行登录，管理他的企业信息」；F 列「爱心企业查看、管理、搜索、注册审核、批量导出，删除，暂停；后台注册企业账号」；Row 49「企业登录」。
--
-- 【第三类主体】既不是 volunteer 也不是 admin_user（V4规划 D5）：独立表 + 第三套 StpLogic（type=enterprise）+ /e/** 路由段。
-- 【状态】0 待审核 / 1 正常 / 2 驳回 / 3 暂停。待审核与驳回的账号能登录、只能看改自己的资料与重新提交；暂停的登录不了、已登录的下一次请求即被踢出。
-- 【唯一】用户名、信用代码在未删除的账号里唯一（生成列唯一键，删除即释放）；用户名不区分大小写（默认排序规则）；信用代码服务端规范化成大写。
-- 【不用 ascii 字符集】后台搜索要对这两列做 LIKE，ascii 列与 utf8mb4 参数会报 Illegal mix of collations（用例当场撞出）；账号只允许字母数字下划线，默认排序规则足够。
-- 【负责人手机】验证码验证过的号，密文 + 哈希（同志愿者 PII）；对外电话 contact_phone 是企业主动公开的，明文。
-- 【本文件形态】建表 + 权限点 INSERT，新库上没有失败的可能。

CREATE TABLE enterprise_account (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键（即企业编号）',
    name                VARCHAR(100)  NOT NULL COMMENT '企业名称（店名）',
    credit_code         VARCHAR(18)   NOT NULL COMMENT '统一社会信用代码（大写）',
    logo_url            VARCHAR(512)  DEFAULT NULL COMMENT '头像 / 照片',
    intro               VARCHAR(2000) DEFAULT NULL COMMENT '企业介绍',
    address             VARCHAR(255)  DEFAULT NULL COMMENT '地址',
    contact_phone       VARCHAR(32)   DEFAULT NULL COMMENT '对外联系电话（公开展示）',
    leader_name         VARCHAR(32)   NOT NULL COMMENT '项目负责人',
    leader_phone        VARCHAR(255)  NOT NULL COMMENT '负责人手机号（密文）',
    leader_phone_hash   VARCHAR(64)   NOT NULL COMMENT '负责人手机号哈希',
    username            VARCHAR(32)   NOT NULL COMMENT '登录账号（不区分大小写）',
    password            VARCHAR(100)  NOT NULL COMMENT '登录密码（BCrypt）',
    status              TINYINT       NOT NULL COMMENT '0 待审核 / 1 正常 / 2 驳回 / 3 暂停',
    source              TINYINT       NOT NULL DEFAULT 1 COMMENT '1 自助注册 / 2 后台代建',
    submit_time         DATETIME      NOT NULL COMMENT '最近一次提交入驻申请的时间',
    reject_reason       VARCHAR(255)  DEFAULT NULL COMMENT '驳回原因',
    audit_by            BIGINT        DEFAULT NULL COMMENT '审核人 admin_user.id',
    audit_time          DATETIME      DEFAULT NULL COMMENT '审核时间',
    pause_reason        VARCHAR(255)  DEFAULT NULL COMMENT '暂停原因',
    paused_by           BIGINT        DEFAULT NULL COMMENT '暂停人 admin_user.id',
    paused_time         DATETIME      DEFAULT NULL COMMENT '暂停时间',
    created_by          BIGINT        DEFAULT NULL COMMENT '后台代建人 admin_user.id',
    last_login_time     DATETIME      DEFAULT NULL COMMENT '最近登录时间',
    create_time         DATETIME      NOT NULL COMMENT '创建时间',
    update_time         DATETIME      DEFAULT NULL COMMENT '更新时间',
    is_deleted          TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    active_username     VARCHAR(32)   GENERATED ALWAYS AS (CASE WHEN is_deleted = 0 THEN username ELSE NULL END) STORED,
    active_credit_code  VARCHAR(18)   GENERATED ALWAYS AS (CASE WHEN is_deleted = 0 THEN credit_code ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_username (active_username),
    UNIQUE KEY uk_active_credit_code (active_credit_code),
    KEY idx_status (status, id),
    KEY idx_leader_phone (leader_phone_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '爱心企业账号';

INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('enterprise:manage', '爱心企业管理（查看 / 搜索 / 后台注册 / 暂停恢复 / 删除）', 'enterprise', 2, 100, NOW(), 0),
('enterprise:audit', '爱心企业入驻审核', 'enterprise', 2, 101, NOW(), 0),
('enterprise:export', '爱心企业批量导出（含负责人手机号）', 'enterprise', 2, 102, NOW(), 0);
