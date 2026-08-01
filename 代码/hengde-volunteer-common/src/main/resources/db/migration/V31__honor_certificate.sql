-- 电子证书核心（V2 第 4 批）。
--
-- 需求原文（`小程序设想【第十版】.xlsx` · 前端 sheet · Row 36）：
--   C 前端信息：「参加完活动后，【自动生成一个盖章的电子证书】，如需纸质证书则需再申请提交收件信息
--                并支付快递费用。【电子证书预览和下载】」
--   F 后端要求：「后台可以【批量上传pdf证书】，亦可【删除指定某人证书】、【设置某个活动的电子样本】、
--                批量设置可以申请纸质证书时间」
-- 原型 `志愿平台设想【第十版】.pdf` P83「协会证书」：按类别分页签、卡片含「证书编号」、按钮「下载PDF」。
--
-- 🛑 本迁移【只建电子证书核心的两张表】。纸质申请的 5 张表（paper_apply / paper_apply_item /
--    paper_claim / paper_fee_rule / paper_window）与支付退款链路整体冻结，不在本批落地，
--    理由见 `文档/v2/V2规划.md` 第 4 批范围声明。i志愿证书（Row 37）属第 4B 批，同样不落表。

-- ── 证书主体 ──
CREATE TABLE honor_certificate (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    cert_no        VARCHAR(64)  NOT NULL COMMENT '证书编号，对外展示（原型 P83 卡片「证书编号：564641541665156」）',
    volunteer_id   BIGINT       NOT NULL COMMENT '归属志愿者 volunteer.id',
    -- 类型维度先建好但本批只放行 1：i志愿证书（2）属第 4B 批，donate 域的助学助困/微心愿/公益捐书/捐物
    -- 数据源在 V3 未建的 donate 模块（P83、P84），故本批只有活动证书这一类。
    type           TINYINT      NOT NULL COMMENT '类型 1活动证书/2i志愿证书（2 属第4B批，本批不产生）',
    activity_id    BIGINT       DEFAULT NULL COMMENT '所属活动 activity.id；i志愿证书为 NULL',
    -- 唯一键维度＝场次。协会 2026-07-30 答复：「志愿者证书是根据他的【场次】来决定的，【一场活动一个证书】」。
    -- 该口径与原型 P15「报名详情」一致（同一人多行、每行各带岗位时间与签到签退）。
    -- V30 已把 activity_attendance 下沉到场次，本键的数据基础于此具备。
    slot_id        BIGINT       DEFAULT NULL COMMENT '所属场次 activity_slot.id；i志愿证书为 NULL',
    -- 【可空是刻意的】：Row 36 要求「参加完自动生成」，故活动确认后立刻写入本行、志愿者当即看得到条目；
    -- PDF 在首次预览/下载时才渲染并回填 file_key（懒渲染）。系统不为从不下载的人白跑渲染。
    -- 存的是【对象存储私有 key】而非 URL——证书走私有对象 + 短期签名 URL，签名 URL 绝不能落库。
    file_key       VARCHAR(512) DEFAULT NULL COMMENT '证书 PDF 的私有对象 key；NULL=尚未渲染',
    source         TINYINT      NOT NULL DEFAULT 1 COMMENT '来源 1系统生成/2后台批量上传/3i志愿导出上传',
    download_count INT          NOT NULL DEFAULT 0 COMMENT '下载次数（Row 37 F 列「下载次数」）',
    generate_time  DATETIME     DEFAULT NULL COMMENT 'PDF 实际渲染完成时间；懒渲染前为 NULL',
    -- 软删口径取「(b) 恢复原记录」：唯一键命中且该行已软删 → 清除软删标记、保留原编号与文件。
    -- 故软删行【仍占用】uk_slot_cert，这正是该口径所需要的：重跑时能命中并恢复，而不是另发新编号。
    is_deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删（Row 36 F「删除指定某人证书」）',
    deleted_by     BIGINT       DEFAULT NULL COMMENT '删除人 admin_user.id',
    deleted_time   DATETIME     DEFAULT NULL COMMENT '删除时间',
    deleted_reason VARCHAR(512) DEFAULT NULL COMMENT '删除原因',
    create_time    DATETIME     DEFAULT NULL,
    update_time    DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cert_no (cert_no),
    -- 业务唯一键：一人一场次一张活动证书。
    -- 【为什么必须是数据库约束】自动创建的触发点是秘书部确认，而秘书部重复确认、补录回放、
    -- 异步重试都会重复触发；只靠 service 里「查一下有没有」在并发下必然双插，志愿者端会实打实看到两条。
    -- 这与第 1 批积分账本 uk_source 存在的理由完全相同。
    -- i志愿证书 slot_id 为 NULL、不受本键约束（MySQL 视多个 NULL 互不相同），这是刻意的。
    UNIQUE KEY uk_slot_cert (type, volunteer_id, slot_id),
    KEY idx_volunteer (volunteer_id),
    KEY idx_activity (activity_id),
    -- 【为什么不能只靠 uk_slot_cert】补偿扫描每小时按 `type = 1 AND slot_id IN (...)` 做差集，
    -- 而唯一键的前导列是 type（只有 1~2 个取值），前缀只吃得到 type=1 那一整段——
    -- 等于每轮把「全部活动证书」扫一遍，成本随历史总量增长而不是随本批场次数。
    KEY idx_slot (slot_id),
    -- 兜住上面那个 NULL 空洞：活动证书若漏填 slot_id，该行会因「多个 NULL 互不相同」完全绕过唯一键，
    -- 一个人能拿到任意多张同一场次的证书。activity_id 同理（列表按活动筛选要用）。
    -- ⚠️ MySQL 8.0.16 起 CHECK 才真正生效，低版本【静默忽略】——部署前须确认服务端版本，否则这道防御等于没写。
    CONSTRAINT ck_cert_activity_scope CHECK (type <> 1 OR (slot_id IS NOT NULL AND activity_id IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '协会证书（电子证书核心）';

-- ── 电子样本（模板）──
-- Row 36 F 列第 ③ 项「设置某个活动的电子样本」——模板绑【活动】，不是全局一套。
CREATE TABLE honor_certificate_template (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    -- 【作用域用显式键，不用可空的 activity_id】：若用 activity_id 唯一键表达「全局默认」，
    -- MySQL 视多个 NULL 互不相同，会放进任意多条「全局默认」，取模板时就不确定命中哪一条。
    -- 故按活动为 'activity:{id}'、全局默认为固定串 'global'，唯一性落在这个显式串上。
    scope_key    VARCHAR(64)  NOT NULL COMMENT "作用域键：'activity:{活动id}' 或 'global'",
    name         VARCHAR(128) NOT NULL COMMENT '样本名称，后台列表展示',
    file_key     VARCHAR(512) NOT NULL COMMENT '样本底图/模板文件的私有对象 key',
    -- 字段与盖章坐标用 JSON 存：不同样本的可变元素数量与位置都不同，拆成固定列会频繁加列。
    layout       JSON         DEFAULT NULL COMMENT '字段与盖章坐标配置',
    enabled      TINYINT      NOT NULL DEFAULT 1 COMMENT '启用 0否/1是',
    create_by    BIGINT       DEFAULT NULL COMMENT '创建人 admin_user.id',
    create_time  DATETIME     DEFAULT NULL,
    update_time  DATETIME     DEFAULT NULL,
    is_deleted   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删/1已删',
    -- 唯一性只约束【未删】的行。
    -- 【为什么不能直接 UNIQUE(scope_key)】样本是逻辑删除，软删行会继续占着这个键：
    -- 管理员删掉某活动的样本后想重建一个，会撞唯一键并收到「该作用域已存在样本，请直接修改它」——
    -- 而列表里根本看不到那条（已软删），于是既建不了也改不了，界面上彻底死锁。
    -- 与证书主体的取舍相反：证书软删【要】继续占键（重跑时复活原件、保留原编号），
    -- 而样本是可替换的配置，删了就该能重建。写法沿用第 3 批 uk_active_grant 的生成列。
    active_scope_key VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 THEN scope_key ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_template_active_scope (active_scope_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '证书电子样本（模板）';

-- ── 补偿扫描要用的考勤索引 ──
-- 本批新增的查询：`secretary_status = 1 AND secretary_time >= ? AND id > ? ORDER BY id LIMIT n`。
-- activity_attendance 原有索引里【没有一条以 secretary_time 打头】（V10 的 uk/idx_volunteer/idx_secretary、
-- V25 的 idx_checkin_time、V30 的 idx_slot 都不行），该查询会走主键顺序扫、把时间谓词降级成逐行过滤：
-- 回看窗口只有 72 小时，代价却随【历史总量】增长，而且每小时跑一次。
--
-- 这和 V25 记过的是同一课（当时是月榜的时间区间吃不到以 volunteer_id 打头的索引）：
-- 【加时间维度过滤时，先看一眼现有索引的前导列】。
--
-- 建成复合而不是单列 (secretary_time)：这条查询永远带 `secretary_status = 1` 的等值条件，
-- 等值列放前导不损失 secretary_time 上的范围扫描能力，同时把状态过滤也吃进索引。
-- 顺带删掉 V10 的 idx_secretary(secretary_status)——新键以它为前缀，它服务的查询新键全能服务，
-- 留着只是白白多一份写放大。
ALTER TABLE activity_attendance
    DROP INDEX idx_secretary,
    ADD KEY idx_secretary_time (secretary_status, secretary_time);

-- ── 权限点 ──
-- 与 V25/V28 同一套写法。type: 2=功能操作 3=审核类。
-- 🛑 纸质相关的 honor:paper-apply / honor:paper-apply-pii 两个点【不入本批】，随纸质路径一并冻结。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('honor:certificate', '证书查询与批量上传', 'honor', 2, 48, NOW(), 0),
('honor:certificate-delete', '证书删除与恢复', 'honor', 2, 49, NOW(), 0),
('honor:certificate-template', '证书电子样本管理', 'honor', 2, 50, NOW(), 0);
