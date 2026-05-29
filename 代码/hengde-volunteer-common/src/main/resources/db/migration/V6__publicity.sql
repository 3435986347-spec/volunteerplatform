-- V6 publicity: banners, announcements and downloadable files.

CREATE TABLE publicity_banner (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    title       VARCHAR(64)  NOT NULL COMMENT '标题',
    image_url   VARCHAR(512) NOT NULL COMMENT '图片地址',
    link_type   TINYINT      NOT NULL DEFAULT 0 COMMENT '0无跳转/1网页/2小程序',
    link_url    VARCHAR(512) DEFAULT NULL COMMENT '跳转地址',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序权重',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '0下架/1上架',
    create_time DATETIME     DEFAULT NULL,
    update_time DATETIME     DEFAULT NULL,
    is_deleted  TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_status_sort (status, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '首页轮播图';

CREATE TABLE publicity_announcement (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    title       VARCHAR(128) NOT NULL COMMENT '标题',
    summary     VARCHAR(255) DEFAULT NULL COMMENT '摘要',
    content     TEXT         DEFAULT NULL COMMENT '正文',
    cover_image_url VARCHAR(512) DEFAULT NULL COMMENT '封面/插图',
    link_type   TINYINT      NOT NULL DEFAULT 0 COMMENT '0无跳转/1网页/2小程序',
    link_url    VARCHAR(512) DEFAULT NULL COMMENT '跳转地址',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '0草稿/1发布',
    publish_time DATETIME    DEFAULT NULL COMMENT '发布时间',
    create_time DATETIME     DEFAULT NULL,
    update_time DATETIME     DEFAULT NULL,
    is_deleted  TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_status_publish (status, publish_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '公告栏';

CREATE TABLE publicity_file (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    file_name   VARCHAR(128) NOT NULL COMMENT '文件名',
    file_url    VARCHAR(512) NOT NULL COMMENT '文件地址',
    file_type   VARCHAR(32)  DEFAULT NULL COMMENT '文件类型',
    file_size   BIGINT       DEFAULT NULL COMMENT '文件大小',
    downloadable TINYINT     NOT NULL DEFAULT 0 COMMENT '是否开放志愿者下载',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序权重',
    create_time DATETIME     DEFAULT NULL,
    update_time DATETIME     DEFAULT NULL,
    is_deleted  TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_download_sort (downloadable, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '公示文件';
