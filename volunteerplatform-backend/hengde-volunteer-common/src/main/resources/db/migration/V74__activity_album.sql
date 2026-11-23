-- 活动相册（V4 活动相册批）：相册 / 上传批次 / 照片 / 积分规则 + 5 个权限点。
--
-- 【需求出处】xlsx Row 11 C「每次活动自动以编号加活动名称为标题自动建立一个相册，显示上传记录，谁上传的，上传了多少张，默认上传原图，
--   需要全部图片的预览，需要预留一个窗口：上传多少张照片给多少积分（需要审核才发放）」；D 列七条权限；F「管理、搜索相册名称、新增相册和删除、上传」；
--   Row 30「到家后可以选择上传活动照片和评论到活动相册，默认勾选发送到交流平台」；V4规划 D10（相册积分是新来源 ALBUM=7、非消费、审核通过才入账）。
--
-- 【自动建册】活动相册在第一次被看到 / 上传时建（get-or-create），标题「编号 活动名称」；生成列唯一键保证一个活动至多一个没删的相册。
--   后台也能新增不挂活动的相册（F 列「新增相册」）。
-- 【积分】按批次：审核员通过时按「这一批现在还没删的照片数」与规则算分，个人在同一相册里累计不超过上限；0 分不入账。
-- 【同步社区】上传时勾选（默认勾），activity 发事件、social 监听发帖（依赖方向 social → activity，V4规划 七）。
-- 【本文件形态】全部新建表 + 规则种子行 + 权限点 INSERT，新库上没有失败的可能。

CREATE TABLE activity_album (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    activity_id         BIGINT       DEFAULT NULL COMMENT '所属活动（后台新增的相册可以不挂活动）',
    title               VARCHAR(160) NOT NULL COMMENT '标题（活动相册自动为「编号 活动名称」）',
    created_by_type     TINYINT      NOT NULL COMMENT '0 系统自动 / 2 后台账号',
    created_by          BIGINT       DEFAULT NULL COMMENT '创建人 admin_user.id',
    deleted_by          BIGINT       DEFAULT NULL COMMENT '删除人 admin_user.id',
    create_time         DATETIME     NOT NULL COMMENT '创建时间',
    update_time         DATETIME     DEFAULT NULL COMMENT '更新时间',
    is_deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    active_activity_key BIGINT GENERATED ALWAYS AS (CASE WHEN is_deleted = 0 THEN activity_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_activity (active_activity_key),
    KEY idx_title (is_deleted, title)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '活动相册（Row 11）';

CREATE TABLE activity_album_batch (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（＝积分流水 source_id，来源 7）',
    album_id        BIGINT       NOT NULL COMMENT '相册',
    uploader_type   TINYINT      NOT NULL COMMENT '1 志愿者 / 2 后台账号',
    uploader_id     BIGINT       NOT NULL COMMENT '上传人',
    photo_count     INT          NOT NULL COMMENT '这一批上传了几张',
    comment         VARCHAR(500) DEFAULT NULL COMMENT '一起提交的评论（Row 30）',
    sync_social     TINYINT      NOT NULL DEFAULT 0 COMMENT '1 同步发到交流平台',
    social_post_id  BIGINT       DEFAULT NULL COMMENT '同步出去的帖子',
    points_status   TINYINT      NOT NULL COMMENT '0 待审核 / 1 已通过 / 2 已驳回 / 9 不参与积分（后台上传、规则关闭时）',
    awarded_points  INT          DEFAULT NULL COMMENT '通过时实际发了几分',
    reject_reason   VARCHAR(255) DEFAULT NULL COMMENT '驳回原因',
    reviewed_by     BIGINT       DEFAULT NULL COMMENT '审核人 admin_user.id',
    reviewed_time   DATETIME     DEFAULT NULL COMMENT '审核时间',
    create_time     DATETIME     NOT NULL COMMENT '上传时间',
    PRIMARY KEY (id),
    KEY idx_album (album_id, id),
    KEY idx_album_uploader (album_id, uploader_type, uploader_id),
    KEY idx_points (points_status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '相册上传批次（上传记录 + 积分审核单）';

CREATE TABLE activity_album_photo (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    album_id      BIGINT       NOT NULL COMMENT '相册',
    batch_id      BIGINT       NOT NULL COMMENT '上传批次',
    uploader_type TINYINT      NOT NULL COMMENT '1 志愿者 / 2 后台账号',
    uploader_id   BIGINT       NOT NULL COMMENT '上传人',
    url           VARCHAR(512) NOT NULL COMMENT '原图地址（只收本系统传到 album/ 下的）',
    deleted_by    BIGINT       DEFAULT NULL COMMENT '删除人 admin_user.id',
    create_time   DATETIME     NOT NULL COMMENT '上传时间',
    update_time   DATETIME     DEFAULT NULL COMMENT '更新时间',
    is_deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_album (album_id, is_deleted, id),
    KEY idx_batch (batch_id, is_deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '相册照片';

CREATE TABLE activity_album_rule (
    id                 BIGINT   NOT NULL COMMENT '恒为 1（单行配置）',
    enabled            TINYINT  NOT NULL DEFAULT 1 COMMENT '1 上传给积分',
    photos_per_unit    INT      NOT NULL DEFAULT 3 COMMENT '每多少张照片',
    points_per_unit    INT      NOT NULL DEFAULT 1 COMMENT '给多少积分',
    max_points_per_album INT    NOT NULL DEFAULT 10 COMMENT '同一个人在同一相册累计最多拿多少分',
    updated_by         BIGINT   DEFAULT NULL COMMENT '最后修改人',
    update_time        DATETIME DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '相册上传积分规则（Row 11「预留一个窗口」，Q10）';

INSERT INTO activity_album_rule (id, enabled, photos_per_unit, points_per_unit, max_points_per_album, update_time)
VALUES (1, 1, 3, 1, 10, NOW());

INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('activity:album', '活动相册管理（搜索 / 新增相册 / 上传 / 积分规则）', 'activity', 2, 88, NOW(), 0),
('activity:album-delete', '删除相册（理事会、宣传部、各部门部长）', 'activity', 2, 89, NOW(), 0),
('activity:album-photo-delete', '删除相册照片（理事会、宣传部、各部门部长、宣传部成员）', 'activity', 2, 90, NOW(), 0),
('activity:album-download', '批量下载相册（理事会、宣传部、各部门部长、宣传部成员）', 'activity', 2, 91, NOW(), 0),
('activity:album-points-audit', '相册上传积分审核', 'activity', 2, 92, NOW(), 0);
