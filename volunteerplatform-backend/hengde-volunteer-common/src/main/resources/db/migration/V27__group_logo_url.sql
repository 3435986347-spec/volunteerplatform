ALTER TABLE volunteer_group
    ADD COLUMN logo_url VARCHAR(512) DEFAULT NULL COMMENT '小组 logo 图片 URL' AFTER description;
