-- 榜样补齐小程序端查询与展示所需的四列（V2 联调 P1-02）。
--
-- 为什么一个 ALTER 里放四个 ADD：MySQL 的 DDL 各自隐式提交，拆成四条语句时
-- 「第 2 条失败」会让库停在做了一半又无法重跑的状态（第 2 次重跑第 1 条撞重名）。
-- 同一张表的多个 ADD COLUMN 是一条语句，要么全成要么全不成。
-- 奖惩表那一列因此另起 V44——跨表就是两条语句了。
--
-- model_type 默认 1（个人）：存量榜样都是人物事迹，默认成团队会让已上架的内容
-- 在小程序的「团队」页签里凭空冒出来。
--
-- link_type 默认 0（不跳转）：新行没填就是不跳转。
-- 【存量已有 link_url 的行由 V44 单独归类】——放在这里不行，ALTER 与 UPDATE 是两条语句，
-- ALTER 隐式提交后 UPDATE 再失败，重跑会撞「列已存在」。
--
-- publish_time 记「第一次上架的时刻」，不是创建时刻：小程序按发布时间排序时，
-- 运营常常先建好一批草稿再统一上架，用 create_time 排会得到录入顺序而不是发布顺序。
-- 存量行一律留 NULL（它们第一次上架是什么时候，库里没有这个事实，编一个不如留空）。
ALTER TABLE honor_role_model
    ADD COLUMN model_type   TINYINT       NOT NULL DEFAULT 1
        COMMENT '类型 1个人/2团队' AFTER title,
    ADD COLUMN summary      VARCHAR(1024) DEFAULT NULL
        COMMENT '简介（小程序列表卡片正文）' AFTER subtitle,
    ADD COLUMN link_type    TINYINT       NOT NULL DEFAULT 0
        COMMENT '跳转类型 0不跳转/1小程序页面/2网页WebView/3外部链接仅复制' AFTER image_url,
    ADD COLUMN publish_time DATETIME      DEFAULT NULL
        COMMENT '首次上架时间；下架不清空，再次上架不覆盖';
