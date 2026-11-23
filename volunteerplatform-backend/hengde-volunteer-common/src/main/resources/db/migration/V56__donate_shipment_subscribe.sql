-- 物流推送（V3 物流推送批）：快递100 订阅推送 + 回调。
--
-- 【需求出处】Row 12 / 17 / 33 / 35「物流轨迹【有快递100的API】」。捐书批只做了查询（轮询），
--   本批把「订阅之后由快递100 主动推」接上：包裹一有新节点就推过来，不必每两小时按次付费去查一遍。
--
-- 【为什么要落库记「订阅过没有」】订阅是**付费项**（《协会待确认清单-v3》⑧：查询与订阅分两项计费）。
--   不记的话，重启、任务重跑、多实例各跑一次都会把同一张运单再订一遍。
--   **落库不放 Redis**——Redis 被清一次会让全部在途运单重新订阅一遍，那是真金白银（与 V39 reminder_sent_time 同形）；
--   **记在运单粒度**——那里是场次，这里是运单。
--
-- 【subscribe_status 五档】0 待订阅 / 1 订阅中（由推送保持快照）/ 2 推送结束（快递100 判定到终态）/
--   3 被快递100 中止（如长时间无轨迹）/ 4 放弃订阅（被拒或失败次数用尽）。
--   **3 和 4 交还给轮询**：订阅这条路走不通时，捐书批的轮询照常兜底，轨迹不会因为订阅失败就再也不更新。
--
-- 【subscribe_salt 每张运单一个】快递100 回调签名是 MD5(param + salt)。全局一个 salt 泄露就能伪造全部运单的推送；
--   每单一个，伪造只能伪造那一单，且回调地址里带着运单 id，验签用的就是那一单自己的 salt。
--   重试订阅**沿用同一个 salt**（首次生成后不再换）：上一次其实已订上、只是应答丢了时，
--   快递100 手里是旧 salt，换了新的就再也验不过它的推送。
--
-- 【本文件形态】一条 ALTER（MySQL 8 的 DDL 原子执行，失败不会停在做了一半）。

ALTER TABLE donate_shipment
    ADD COLUMN subscribe_status       TINYINT      NOT NULL DEFAULT 0 COMMENT '快递100 订阅：0待订阅/1订阅中/2推送结束/3被中止/4放弃订阅（3、4 交还轮询）',
    ADD COLUMN subscribe_salt         VARCHAR(64)           DEFAULT NULL COMMENT '回调验签 salt（每单一个，首次生成后不再换）',
    ADD COLUMN subscribe_attempts     INT          NOT NULL DEFAULT 0 COMMENT '已发起订阅的次数',
    ADD COLUMN subscribe_attempt_time DATETIME              DEFAULT NULL COMMENT '上次发起订阅的时间（重试间隔以它为准）',
    ADD COLUMN subscribe_time         DATETIME              DEFAULT NULL COMMENT '订阅成功时间',
    ADD COLUMN subscribe_error        VARCHAR(255)          DEFAULT NULL COMMENT '上次订阅失败的原因（快递100 返回码 + 文字）',
    ADD COLUMN push_time              DATETIME              DEFAULT NULL COMMENT '上次收到快递100 推送的时间',
    -- 「待订阅」扫描走这条：前导列是订阅状态与终态标记，集合只含还没订上的在途运单，不随历史单量增长
    ADD KEY idx_subscribe_pending (subscribe_status, track_done, subscribe_attempt_time);
