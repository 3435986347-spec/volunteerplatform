-- 志愿者站内提示（V2 第 5 批收口）。
--
-- 【需求出处】xlsx Row 41 F：「各类违规记录和奖励均需组织部同学审核才可显示，
--   **审核之后，志愿者会收到提示**，并有 7 天申诉期」。
--   V32 只做了后半句（7 天窗口），「收到提示」一直缺着——本表补上。
--
-- 【为什么落 auth 域】与 volunteer_sanction 同一条理由：写入方会越来越多
--   （本批是 honor 的奖惩审核；将来报名审核在 activity、小组审批在 organization），
--   把表放进其中任何一个业务域，其余域就要反向依赖它。auth 是所有业务模块都已经
--   依赖的那一层，放这里不新增任何依赖边。
--
-- 【为什么是站内提示，不是短信/微信订阅消息】需求只写「收到提示」，没指定渠道。
--   而外部渠道都卡在协会侧的外部前置上：火山短信的**通知类**模板要以协会名义单独报备
--   （与验证码类流程不同，第 4B 批已记为阻断项），微信订阅消息要协会小程序的模板 id。
--   站内提示是**当前唯一能自己交付完**的那一半，且它同时是别的渠道的落点——
--   将来接短信/订阅消息时，写入点仍是这张表所在的那一处，不必再改调用方。
--   ⚠️ 因此「提示」目前只在志愿者**打开小程序时**看得到，没有推送。这是实现边界，
--   已写进《功能清单》与《协会待确认清单》，不要读成「通知已全部做完」。
--
-- 【两条索引而不是一条】默认视图是「我的全部提示」按 id 倒序翻页，角标是「未读条数」。
--   单一的 (volunteer_id, is_read, id) 吃不到前者的排序（中间隔着 is_read），会退化成 filesort；
--   而只有 (volunteer_id, id) 又让未读计数要回表过滤。各建一条，与 V25/V31/V32 同一课。
--
-- 【不设唯一键】「同一张单不重复提示」由写入侧保证：审核通过那一步是 CAS
--   （只有 review_status 仍为待审核才更新成功），提示与它同事务，故只可能写一次。
--   在这里加唯一键反而要为逻辑删除再造一个生成列，收益不抵复杂度。
CREATE TABLE volunteer_notification (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    volunteer_id BIGINT        NOT NULL COMMENT '接收人 volunteer.id',
    type         TINYINT       NOT NULL DEFAULT 1 COMMENT '提示类型 1奖惩审核通过/2申诉受理结果',
    title        VARCHAR(128)  NOT NULL COMMENT '标题，列表页直接展示',
    content      VARCHAR(1024) NOT NULL COMMENT '正文',
    biz_type     TINYINT       DEFAULT NULL COMMENT '关联业务类型 1奖惩单',
    biz_id       BIGINT        DEFAULT NULL COMMENT '关联业务 id；前端据此跳转详情',
    is_read      TINYINT       NOT NULL DEFAULT 0 COMMENT '0未读/1已读',
    read_time    DATETIME      DEFAULT NULL COMMENT '读取时间',
    create_time  DATETIME      DEFAULT NULL,
    update_time  DATETIME      DEFAULT NULL,
    is_deleted   TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    PRIMARY KEY (id),
    -- 列表：WHERE volunteer_id = ? ORDER BY id DESC
    KEY idx_volunteer (volunteer_id, id),
    -- 角标：WHERE volunteer_id = ? AND is_read = 0
    KEY idx_volunteer_unread (volunteer_id, is_read)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '志愿者站内提示（Row 41 F「志愿者会收到提示」）';
