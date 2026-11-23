-- 系统治理（V4 系统治理批）：xlsx Row 62 日志记录 / Row 71 文件功能（类似网盘）+ Row 19 通知公告的内置文件与定时开放 /
-- Row 75 编号命名 / Row 77 功能排序 / Row 78 界面水印 / Row 63 到家定位的详细地址可见性。
--
-- 【日志只追加】没有删除入口，唯一的删除路径是按保留期的定时清理（V4规划 Q8：保留 180 天）。
--   记的是「谁在什么时候动了什么」，所以它自己不能被谁悄悄改掉——连 update_time 都不留。
-- 【配置一键一行】水印与菜单排序都是「后台配、前端渲染」的小块 JSON，各建一张表不如一张 key/value：
--   它们没有查询维度，只有「读当前这份」和「整份覆盖」。
-- 【编号取号是一条语句】`INSERT … ON DUPLICATE KEY UPDATE current_no = LAST_INSERT_ID(current_no + 1)`，
--   先读再写会在并发下发出两个一样的号（本项目为「先查再动」栽过很多次）。
-- 【文件开放窗口按时间现算】不靠定时任务改状态位——漏跑一次就会让本该开放的文件一直关着（同名单公示、处置到期那一课）。

CREATE TABLE sys_operation_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    log_type    TINYINT      NOT NULL DEFAULT 1 COMMENT '1 操作（写 / 敏感读，由拦截器记）/ 2 页面访问（前端上报）',
    actor_type  TINYINT      NOT NULL DEFAULT 1 COMMENT '操作人类型 1 后台账号 / 2 志愿者 / 3 爱心企业 / 0 未登录',
    actor_id    BIGINT       DEFAULT NULL COMMENT '操作人 id',
    actor_name  VARCHAR(64)  DEFAULT NULL COMMENT '操作人姓名快照（账号日后改名或删除，日志仍要说得清是谁）',
    department  VARCHAR(32)  DEFAULT NULL COMMENT '操作人部门快照',
    action      VARCHAR(128) DEFAULT NULL COMMENT '做了什么（端点摘要 / 页面名）',
    method      VARCHAR(8)   DEFAULT NULL COMMENT 'HTTP 方法',
    uri         VARCHAR(255) DEFAULT NULL COMMENT '路径（不含域名）',
    query       VARCHAR(512) DEFAULT NULL COMMENT '查询串（截断；不记请求体——里面可能有密码）',
    ip          VARCHAR(64)  DEFAULT NULL COMMENT '客户端 IP',
    user_agent  VARCHAR(255) DEFAULT NULL COMMENT 'User-Agent（截断）',
    success     TINYINT      NOT NULL DEFAULT 1 COMMENT '1 成功 / 0 失败',
    error_msg   VARCHAR(255) DEFAULT NULL COMMENT '失败原因（截断）',
    cost_ms     INT          DEFAULT NULL COMMENT '耗时毫秒',
    create_time DATETIME     NOT NULL COMMENT '发生时间',
    PRIMARY KEY (id),
    KEY idx_time (create_time),
    KEY idx_actor (actor_type, actor_id, create_time),
    KEY idx_type_time (log_type, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作日志与页面访问（Row 62，只追加）';

CREATE TABLE sys_config (
    config_key  VARCHAR(64)  NOT NULL COMMENT '配置键：watermark 界面水印 / menu-order 后台菜单排序',
    config_value TEXT        DEFAULT NULL COMMENT '配置值（JSON）',
    updated_by  BIGINT       DEFAULT NULL COMMENT '最后修改人 admin_user.id',
    update_time DATETIME     DEFAULT NULL COMMENT '最后修改时间',
    PRIMARY KEY (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '后台可配的小块配置（Row 77 / Row 78）';

CREATE TABLE sys_serial (
    segment     CHAR(3)      NOT NULL COMMENT '功能编号段（前三位，Row 75）',
    name        VARCHAR(64)  NOT NULL COMMENT '这一段是什么',
    current_no  BIGINT       NOT NULL DEFAULT 0 COMMENT '当前序号（后七位）',
    update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (segment)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '十位编号的功能段与流水（Row 75）';

CREATE TABLE sys_folder (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    parent_id   BIGINT       DEFAULT NULL COMMENT '上级文件夹；NULL 为根',
    name        VARCHAR(128) NOT NULL COMMENT '名称',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序权重',
    create_by   BIGINT       DEFAULT NULL COMMENT '创建人 admin_user.id',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
    is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_parent (parent_id, sort, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件网盘的文件夹（Row 71）';

CREATE TABLE sys_file (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    folder_id     BIGINT       NOT NULL COMMENT '所在文件夹',
    serial_no     CHAR(10)     DEFAULT NULL COMMENT '十位编号（Row 75，功能段 + 流水）',
    name          VARCHAR(255) NOT NULL COMMENT '文件名',
    file_url      VARCHAR(512) NOT NULL COMMENT '对象存储地址',
    file_ext      VARCHAR(16)  DEFAULT NULL COMMENT '扩展名',
    file_size     BIGINT       DEFAULT NULL COMMENT '字节数',
    upload_by     BIGINT       DEFAULT NULL COMMENT '上传人 admin_user.id',
    published     TINYINT      NOT NULL DEFAULT 0 COMMENT '1 公开到小程序「文件下载」板块（Row 19）',
    publish_start DATETIME     DEFAULT NULL COMMENT '开放开始时间；空＝立即',
    publish_end   DATETIME     DEFAULT NULL COMMENT '开放结束时间；空＝不设截止',
    allow_download TINYINT     NOT NULL DEFAULT 1 COMMENT '1 允许志愿者下载（Row 19「开放 / 关闭下载」）',
    create_time   DATETIME     NOT NULL COMMENT '创建时间',
    update_time   DATETIME     DEFAULT NULL COMMENT '更新时间',
    is_deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_serial (serial_no),
    KEY idx_folder (folder_id, id),
    KEY idx_published (published, publish_start, publish_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件网盘的文件（Row 71 / Row 19）';

CREATE TABLE sys_folder_grant (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    folder_id   BIGINT      NOT NULL COMMENT '文件夹',
    grantee_type TINYINT    NOT NULL COMMENT '授给谁 1 后台账号 / 2 部门',
    admin_id    BIGINT      DEFAULT NULL COMMENT '后台账号 id（类型 1）',
    department  VARCHAR(32) DEFAULT NULL COMMENT '部门（类型 2）',
    can_write   TINYINT     NOT NULL DEFAULT 0 COMMENT '1 可上传 / 改 / 删；0 只读',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    grant_key   VARCHAR(80) GENERATED ALWAYS AS (
        CONCAT(folder_id, ':', grantee_type, ':', COALESCE(admin_id, department))) STORED COMMENT '同一对象在同一文件夹上只有一条授权',
    PRIMARY KEY (id),
    UNIQUE KEY uk_grant (grant_key),
    KEY idx_folder (folder_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件夹授权（Row 71「可以设权限」）';

CREATE TABLE sys_file_share (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    file_id      BIGINT      NOT NULL COMMENT '被分享的文件',
    token        VARCHAR(64) NOT NULL COMMENT '分享令牌（随机，不是 id）',
    require_login TINYINT    NOT NULL DEFAULT 1 COMMENT '1 要登录后台账号才能打开（Row 71 原文的开关）',
    expire_time  DATETIME    DEFAULT NULL COMMENT '过期时间；空＝不过期',
    download_count INT       NOT NULL DEFAULT 0 COMMENT '下载次数',
    create_by    BIGINT      DEFAULT NULL COMMENT '分享人 admin_user.id',
    create_time  DATETIME    NOT NULL COMMENT '创建时间',
    is_deleted   TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除（撤销分享）',
    active_token VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN is_deleted = 0 THEN token ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_token (active_token),
    KEY idx_file (file_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件分享链接（Row 71「可以分享、下载」）';

ALTER TABLE activity_attendance
    ADD COLUMN confirm_home_address VARCHAR(255) DEFAULT NULL COMMENT '确认到家时上报的详细地址（Row 63；只对持 activity:home-address 的账号下发）' AFTER confirm_home_lng;

INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('system:log', '操作日志查看（Row 62「最高权限才可查看」，默认不授任何人）', 'system', 2, 110, NOW(), 0),
('system:config', '系统配置（界面水印 / 后台菜单排序 / 编号段）', 'system', 2, 111, NOW(), 0),
('system:file', '文件网盘（文件夹 / 文件 / 授权 / 分享 / 公开到小程序）', 'system', 2, 112, NOW(), 0),
('activity:home-address', '查看到家详细地址（Row 63；没有这个点只看得到「已到家」，默认不授任何人）', 'activity', 2, 113, NOW(), 0);

INSERT INTO sys_serial (segment, name, current_no, update_time) VALUES
('101', '文件网盘文件', 0, NOW());
