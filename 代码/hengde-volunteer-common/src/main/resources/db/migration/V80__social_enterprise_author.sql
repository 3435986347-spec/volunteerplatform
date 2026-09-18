-- 爱心企业发帖（V4 爱心企业批·社区段）：xlsx Row 15 F「发帖：登录企业账号之后也可以进行发帖，和平台志愿者无异，只不过主页是爱心企业的主页」。
--
-- 【复用 social_post，不另建表】帖子流、审核、关键词风控、举报、点赞评论都挂在这张表上；另起一张表等于把这些全再写一遍。
-- 【作者名与头像存快照】social 不依赖 enterprise（依赖方向是 enterprise → social），渲染帖子流时拿不到企业资料；
--   企业改名之后老帖子显示的是发帖时的名字，这与官方帖快照部门是同一口径。
-- 【本文件形态】单条 ALTER（三个子句同属一条语句），新库上没有失败的可能。

ALTER TABLE social_post
    MODIFY COLUMN author_type TINYINT NOT NULL COMMENT '1 志愿者 / 2 官方（后台账号）/ 3 爱心企业',
    ADD COLUMN author_snapshot_name VARCHAR(100) DEFAULT NULL COMMENT '企业帖：发帖时的企业名称快照' AFTER official_label,
    ADD COLUMN author_snapshot_avatar VARCHAR(512) DEFAULT NULL COMMENT '企业帖：发帖时的企业头像快照' AFTER author_snapshot_name;
