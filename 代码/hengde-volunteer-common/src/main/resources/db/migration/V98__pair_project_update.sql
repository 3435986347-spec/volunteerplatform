-- 结对项目的「活动动态」（协会 2026-10-16 答复五 丙-2；原型 P48 / P102 的发放凭证一栏）：
-- 协会把助学金 / 物资发到受助人手里时拍的凭证，图文，所有能看项目的人都看得到。
-- 与「受助方来信」（V52）分开：来信是受助方写给结对人的、可以只给某一个人看；动态是协会的发放记录，一律公开。

CREATE TABLE donate_pair_update (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_id  BIGINT        NOT NULL COMMENT '结对项目',
    content     VARCHAR(2000) NOT NULL COMMENT '动态内容',
    image_urls  VARCHAR(5000)          DEFAULT NULL COMMENT '换行分隔的图片 URL（最多 9 张）',
    happen_time DATETIME      NOT NULL COMMENT '发生时间（发放那天；不填取录入时间）',
    create_by   BIGINT        NOT NULL COMMENT '录入人 admin_user.id',
    create_time DATETIME      NOT NULL COMMENT '创建时间',
    update_time DATETIME               DEFAULT NULL COMMENT '更新时间',
    is_deleted  TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_project (project_id, happen_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '结对项目的活动动态（发放凭证）';
