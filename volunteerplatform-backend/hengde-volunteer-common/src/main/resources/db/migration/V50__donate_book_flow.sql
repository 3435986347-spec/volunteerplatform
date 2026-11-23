-- 公益捐书与物资流转地基（V3 捐书批）。
--
-- 【需求出处】xlsx Row 17 C/D/F（捐书 10 步流程 + 三级条码 + 10 维搜索 + 批量导出）、
--   Row 64（后台同一流程）、Row 38（捐书记录 / 运单管理）、Row 33 D（商品条码数据库）。
--
-- 【为什么是 V50】卷批占了 V46–V49（2026-09-16 跨分支核对：其余分支最大 V45）。
--
-- 【为什么叫「物资流转地基」而不是「捐书表」】微心愿（Row 12 G：录入物资 → 包裹 → 到货 → 核对 →
--   专属条码 → 反馈图片）与众筹捐物（Row 16）走的是同一条流水线。所以运单与物资带 biz_type + biz_id，
--   不把「捐书活动」写死成外键——后两批只需要新的 biz_type，不需要新的运单表。
--
-- 【三级条码 + 一种核对码】
--   ① 物品专属码 HDI…（Row 17 第 7 步「给每一个捐赠物品生成一个专属条形码」，我们生成，核对合格后才发）；
--   ② 箱码 HDB…（第 9 步「生成箱子条形码，把物品扫码装入箱子」）；
--   ③ 快递单号（第 4/5 步，快递公司的码，不是我们生成的）；
--   另有 ④ 商品条码（ISBN 之类，Row 17 D「捐赠人在商品里面输入那本书，即可跳出那本书的信息」）——
--   它只用于录入时核对，不参与流转，存在 donate_barcode_catalog 与物资的 catalog_barcode 列上。
--   前缀收在 com.hengde.donate.constant.DonateCodes 一处（仿 AttendanceQr 的单一来源）。
--
-- 【受赠单位是主数据表】《协会待确认清单-v3》⑤ 默认：Row 17 F 要按「受捐单位」搜索导出，
--   Row 38 要在志愿者端展示「受捐学校」——自由文本一个错别字就把统计打散。
--   送达时把单位名**快照**进箱子与物资（recipient_org_name）：单位改名不该改写已送达的记录。
--
-- 【借阅次数只存字段】清单 ⑥ 默认：全部材料只有 Row 38 一处提到「借阅次数」，没有借阅流程、
--   没有入口、没说谁登记——只留列（默认 0，后台可改），不发明借阅流程。
--
-- 【快递100 本批只做查询】清单 ⑧：订阅推送要备案后的公网 HTTPS 回调，属物流推送批。
--   查询结果**快照落库**（track_* 列）：外部不可用时页面仍要能看。
--   ⚠️ 轮询必须有终态停止条件：签收 / 退回 / 我们已确认到货的运单把 track_done 置 1、退出待办集合，
--   否则扫描集合随历史单量单调增长。扫描条件走 idx_track_pending(track_done, track_query_time)，
--   与 V39 idx_reminder_pending 同形。
--
-- 【一个快递单号只登记一次】生成列 active_express_key 只在「未删且未取消」时取值，
--   写法同 V31 active_scope_key：取消的运单释放单号（捐赠人填错单号取消后重填，不该被自己卡死）。
--   键里带快递公司编码：不同公司的单号理论上可能相同。
--
-- 【退回收件人电话是 PII，密文存储】与 volunteer.phone 同一纪律（CryptoUtil AES-GCM），
--   长度按密文留足 255。收件地址与 volunteer.address 同口径明文。
--
-- 【本文件形态】全部是新建表 + 一条 INSERT，同 V41 / V46。

CREATE TABLE donate_recipient_org (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name            VARCHAR(128) NOT NULL COMMENT '受赠单位名称',
    org_type        TINYINT      NOT NULL DEFAULT 1 COMMENT '1学校/2乡镇/3其他',
    address         VARCHAR(255)          DEFAULT NULL COMMENT '地址',
    contact_name    VARCHAR(64)           DEFAULT NULL COMMENT '联系人',
    contact_phone   VARCHAR(32)           DEFAULT NULL COMMENT '联系电话（单位座机 / 负责人电话，非志愿者 PII）',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用/0停用——停用只挡新的送达选择，已送达记录不受影响',
    sort            INT          NOT NULL DEFAULT 0 COMMENT '排序',
    create_by       BIGINT                DEFAULT NULL COMMENT '创建人 admin_user.id',
    create_time     DATETIME     NOT NULL COMMENT '创建时间',
    update_time     DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    -- 名称在未删行中唯一：同一所学校录两遍，统计就被拆成两行
    active_name_key VARCHAR(128) GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 THEN name ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_name (active_name_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '受赠单位主数据（清单⑤默认）';

CREATE TABLE donate_barcode_catalog (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    barcode            VARCHAR(64)  NOT NULL COMMENT '商品条码（ISBN / EAN 等）',
    name               VARCHAR(255) NOT NULL COMMENT '商品名（书名）',
    item_type          TINYINT      NOT NULL DEFAULT 1 COMMENT '1课外书籍/2学习用品/3运动器材/9其他',
    spec               VARCHAR(255)          DEFAULT NULL COMMENT '规格 / 作者 / 出版社（自由文本）',
    remark             VARCHAR(255)          DEFAULT NULL COMMENT '备注',
    create_time        DATETIME     NOT NULL COMMENT '创建时间',
    update_time        DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    active_barcode_key VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 THEN barcode ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_barcode (active_barcode_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品条码数据库（Row 17 D / Row 33 D）';

CREATE TABLE donate_campaign (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    title          VARCHAR(128) NOT NULL COMMENT '活动名称',
    cover_url      VARCHAR(512)          DEFAULT NULL COMMENT '封面',
    detail         TEXT                  DEFAULT NULL COMMENT '详情（文字 + 图片）',
    start_time     DATETIME     NOT NULL COMMENT '活动开始时间',
    end_time       DATETIME     NOT NULL COMMENT '活动结束时间',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0草稿/1已发布/2已结束（手动结束）；未开始/进行中按时间现算',
    recv_phone     VARCHAR(32)           DEFAULT NULL COMMENT '收件电话（Row 17「电话：后台预留」）',
    recv_address   VARCHAR(255)          DEFAULT NULL COMMENT '收件地址前缀（Row 17「地址：后台预留+【捐赠人姓名】」）',
    stats_deadline DATETIME              DEFAULT NULL COMMENT '本次活动数据的统计截止时间（Row 17 C）',
    create_by      BIGINT                DEFAULT NULL COMMENT '创建人 admin_user.id',
    create_time    DATETIME     NOT NULL COMMENT '创建时间',
    update_time    DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_status_time (status, start_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '公益捐书活动（Row 17）';

CREATE TABLE donate_shipment (
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    biz_type               TINYINT      NOT NULL COMMENT '来源 1捐书活动（2微心愿/3众筹捐物留给后续批次）',
    biz_id                 BIGINT       NOT NULL COMMENT '来源 id（捐书活动 id 等）',
    donor_volunteer_id     BIGINT       NOT NULL COMMENT '捐赠人 volunteer.id',
    donor_name             VARCHAR(64)  NOT NULL COMMENT '【登记时快照】捐赠人姓名——收件人写的就是它（Row 17）',
    donor_org              VARCHAR(128)          DEFAULT NULL COMMENT '捐赠人单位（捐赠人自填，Row 17 F 搜索与导出列）',
    express_code           VARCHAR(32)  NOT NULL COMMENT '快递公司编码（快递100 口径，见 ExpressCompany）',
    express_company        VARCHAR(64)  NOT NULL COMMENT '快递公司名称快照',
    express_no             VARCHAR(64)  NOT NULL COMMENT '快递单号',
    status                 TINYINT      NOT NULL DEFAULT 1 COMMENT '1已寄出/2已到货/3已核对/4已取消',
    ship_time              DATETIME     NOT NULL COMMENT '登记寄出时间',
    arrive_time            DATETIME              DEFAULT NULL COMMENT '扫码确认到货时间',
    arrive_by              BIGINT                DEFAULT NULL COMMENT '确认到货人 admin_user.id',
    check_time             DATETIME              DEFAULT NULL COMMENT '核对完成时间',
    check_by               BIGINT                DEFAULT NULL COMMENT '核对人 admin_user.id',
    return_status          TINYINT      NOT NULL DEFAULT 0 COMMENT '退回：0无需退回/1待捐赠人提交收件信息/2待寄回/3已寄回',
    return_name            VARCHAR(64)           DEFAULT NULL COMMENT '退回收件人',
    return_phone           VARCHAR(255)          DEFAULT NULL COMMENT '退回收件电话（**密文**，CryptoUtil）',
    return_address         VARCHAR(255)          DEFAULT NULL COMMENT '退回收件地址',
    return_submit_time     DATETIME              DEFAULT NULL COMMENT '捐赠人提交退回收件信息时间',
    return_express_code    VARCHAR(32)           DEFAULT NULL COMMENT '退回快递公司编码',
    return_express_company VARCHAR(64)           DEFAULT NULL COMMENT '退回快递公司名称',
    return_express_no      VARCHAR(64)           DEFAULT NULL COMMENT '退回快递单号（Row 17 D「我们这边会给他上传快递单号」）',
    return_time            DATETIME              DEFAULT NULL COMMENT '寄回时间',
    return_by              BIGINT                DEFAULT NULL COMMENT '寄回登记人 admin_user.id',
    track_state            TINYINT               DEFAULT NULL COMMENT '【快递100 快照】物流状态码（0在途/1揽收/2疑难/3签收/4退签/5派件/6退回…）',
    track_last_context     VARCHAR(512)          DEFAULT NULL COMMENT '【快递100 快照】最新一条轨迹文字',
    track_last_time        DATETIME              DEFAULT NULL COMMENT '【快递100 快照】最新一条轨迹时间',
    track_json             MEDIUMTEXT            DEFAULT NULL COMMENT '【快递100 快照】完整轨迹（外部不可用时页面仍要能看）',
    track_query_time       DATETIME              DEFAULT NULL COMMENT '上次向快递100 查询的时间',
    track_done             TINYINT      NOT NULL DEFAULT 0 COMMENT '1=已到终态（签收/退回/已确认到货/已取消），退出轮询集合',
    create_time            DATETIME     NOT NULL COMMENT '创建时间',
    update_time            DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    active_express_key     VARCHAR(160) GENERATED ALWAYS AS (
        CASE WHEN is_deleted = 0 AND status <> 4 THEN CONCAT(express_code, '#', express_no) ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_express (active_express_key),
    KEY idx_biz_status (biz_type, biz_id, status),
    KEY idx_donor (donor_volunteer_id, id),
    KEY idx_express_no (express_no),
    KEY idx_track_pending (track_done, track_query_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '捐赠运单 / 包裹（Row 17 第 4 步，Row 38 运单管理）';

CREATE TABLE donate_item (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    shipment_id        BIGINT       NOT NULL COMMENT '所属运单',
    biz_type           TINYINT      NOT NULL COMMENT '冗余自运单：来源类型（搜索与统计不必每次 JOIN 运单）',
    biz_id             BIGINT       NOT NULL COMMENT '冗余自运单：来源 id',
    donor_volunteer_id BIGINT       NOT NULL COMMENT '冗余自运单：捐赠人（「我的捐书记录」按人查）',
    name               VARCHAR(255) NOT NULL COMMENT '物资名称',
    item_type          TINYINT      NOT NULL DEFAULT 1 COMMENT '1课外书籍/2学习用品/3运动器材/9其他（Row 17 C「本次活动数据」三类）',
    quantity           INT          NOT NULL DEFAULT 1 COMMENT '数量（Row 17 F 导出列「物资数量」）',
    catalog_barcode    VARCHAR(64)           DEFAULT NULL COMMENT '商品条码（ISBN 等），录入时核对用',
    exclusive_code     VARCHAR(32)           DEFAULT NULL COMMENT '物品专属码 HDI…，核对合格后生成，一次性（第 7 步）',
    status             TINYINT      NOT NULL DEFAULT 0 COMMENT '0待到货/1已到货待核对/2合格/3不合格待退回/4已退回/5已装箱/6已送达/7已取消',
    check_remark       VARCHAR(255)          DEFAULT NULL COMMENT '核对说明（不合格原因）',
    box_id             BIGINT                DEFAULT NULL COMMENT '当前所在箱子',
    recipient_org_id   BIGINT                DEFAULT NULL COMMENT '受赠单位（送达时写）',
    recipient_org_name VARCHAR(128)          DEFAULT NULL COMMENT '【送达时快照】受赠单位名（Row 38「受捐学校」）',
    deliver_time       DATETIME              DEFAULT NULL COMMENT '送达时间',
    borrow_count       INT          NOT NULL DEFAULT 0 COMMENT '借阅次数（清单⑥：只存字段、不做借阅流程）',
    added_by           BIGINT                DEFAULT NULL COMMENT '后台单独添加时的操作人 admin_user.id（Row 17「后台也可单独添加」）',
    create_time        DATETIME     NOT NULL COMMENT '创建时间',
    update_time        DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（后台「单独减少物品」）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_exclusive_code (exclusive_code),
    KEY idx_shipment (shipment_id),
    KEY idx_box (box_id),
    KEY idx_biz_status (biz_type, biz_id, status),
    KEY idx_donor (donor_volunteer_id, id),
    KEY idx_catalog_barcode (catalog_barcode),
    KEY idx_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '捐赠物资（Row 17 / Row 38 捐书记录）';

CREATE TABLE donate_box (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    box_code           VARCHAR(32)  NOT NULL COMMENT '箱码 HDB…（第 9 步）',
    biz_type           TINYINT      NOT NULL COMMENT '来源类型',
    biz_id             BIGINT       NOT NULL COMMENT '来源 id',
    status             TINYINT      NOT NULL DEFAULT 0 COMMENT '0装箱中/1已送达',
    recipient_org_id   BIGINT                DEFAULT NULL COMMENT '送达的受赠单位',
    recipient_org_name VARCHAR(128)          DEFAULT NULL COMMENT '【送达时快照】受赠单位名',
    deliver_time       DATETIME              DEFAULT NULL COMMENT '送达时间（第 10 步）',
    deliver_by         BIGINT                DEFAULT NULL COMMENT '送达确认人 admin_user.id',
    create_by          BIGINT                DEFAULT NULL COMMENT '建箱人 admin_user.id',
    create_time        DATETIME     NOT NULL COMMENT '创建时间',
    update_time        DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_box_code (box_code),
    KEY idx_biz_status (biz_type, biz_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '装箱（Row 17 第 9–10 步）';

-- 【轨迹对捐赠人公开】Row 17「以上操作轨迹捐赠人均可在他们的前端看得到记录」。
--   只追加、不修改：轨迹是给人看的历史，改了就不叫轨迹。运单级动作（到货）item_id 为 NULL。
CREATE TABLE donate_item_trace (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    shipment_id   BIGINT       NOT NULL COMMENT '运单',
    item_id       BIGINT                DEFAULT NULL COMMENT '物资；运单级动作为 NULL',
    box_id        BIGINT                DEFAULT NULL COMMENT '箱子（装箱 / 出箱 / 送达时有值）',
    action        TINYINT      NOT NULL COMMENT '动作码，见 DonateTraceAction',
    content       VARCHAR(512) NOT NULL COMMENT '给捐赠人看的一句话',
    operator_type TINYINT      NOT NULL DEFAULT 0 COMMENT '0系统/1管理员/2捐赠人',
    operator_id   BIGINT                DEFAULT NULL COMMENT '操作人 id（按 operator_type 解读）',
    create_time   DATETIME     NOT NULL COMMENT '发生时间',
    update_time   DATETIME              DEFAULT NULL COMMENT '更新时间',
    is_deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除；本表不提供删除入口',
    PRIMARY KEY (id),
    KEY idx_shipment (shipment_id, id),
    KEY idx_item (item_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '物资流转轨迹（对捐赠人公开）';

-- 权限点：本批 2 个。流转动作、10 维搜索、活动 / 受赠单位 / 条码库维护同一个 donate:item；
-- 批量导出单拆 donate:item-export——Row 17 F 的导出列含「捐赠人名字、电话、单位、志愿者码链接」，
-- 与志愿者名册同一类数据（同 user:list / user:export 的先例）。
-- 改这里必须同步 OrganizationRbacTest.permissionsSeeded（58 → 60）。
INSERT INTO permission (code, name, module, type, sort, create_time, is_deleted) VALUES
('donate:item',        '捐赠物资流转与搜索', 'donate', 2, 67, NOW(), 0),
('donate:item-export', '捐赠物资批量导出',   'donate', 1, 68, NOW(), 0);
