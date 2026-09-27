# 接口 URL 约束文档 V3

> **本文件是 V3（积分商城 / 捐赠域 / 平台级收付）端点的约束清单。**
> V1 → V2 的全量端点仍以 [`文档/v2/url文档v2.md`](../v2/url文档v2.md) 为准，**两份并存、互不覆盖**。
>
> ✅ **2026-09-17 收尾批定稿**：规划时登记的路径已全部落地，没有剩下「⬜ 未实现」的行；
> 契约脚本 `tools/verify_url_contract.py` 同时读 v2 与 v3 两份文档（前置批已改）。
> ⚠️ **「已实现」不等于「可用」**：凡是经过微信支付的端点（商城现金部分、快递费现金、在线捐款、退款、对账）在协会商户资质下来之前
> 一律被明确拒绝或标 `skipped`，只在假渠道上验证过；快递100 推送回调同理（要备案域名）。
>
> ⚠️ **脚本能证明「注解写对了」，不能证明「端点真的注册进了 Spring」**——
> V2 第 5 批那次 javadoc 漏了 `*/` 把整个 `@GetMapping` 吞进注释，`mvn` 与契约脚本**同时放过**，
> 只有 `javap -p` 照得出来。这条限制在 V3 同样成立。

## 设计规范（继承 V2，仅列 V3 新增或需要强调的）

| 规范项 | 值 |
|---|---|
| context-path | `/api`（由 `server.servlet.context-path` 配置，Controller 代码中不写） |
| 完整 URL 格式 | `https://{host}/api/{role}/{domain}/{resource}` |
| 角色前缀 `/v` `/a` `/e` | 志愿者端 / 管理后台 / 爱心企业端（`/e` 仍暂缓） |
| **新增：`/callback/**`** | **无角色前缀的 webhook 段**，见下方专节 |
| 领域段 | 商城与捐赠统一用 `donate`（`/v/donate/...`、`/a/donate/...`）；收付用 `trade`（仅 `/a/trade/...`） |
| 分页 / 搜索 | 同 V2：`page`（从 1）、`size`（默认 10，最大 100）、`keyword=` |

> **领域段为什么是 `donate` 而不是 `mall`**：与权限点前缀一致（承重条款 2）。
> **表名**才按聚合根概念分 `mall_` / `donate_`——URL 与权限点按模块，表名按概念，两套规则各有各的理由。

---

## `/callback/**` —— webhook 段（V3 新增的路径形态）

**这是 V3 引入的第一类没有角色前缀的端点**，形态在此定死，免得实现时各写一套：

| 项 | 约定 |
|---|---|
| 路径 | `/callback/{provider}/{event}`，例：`/callback/trade/wechat/pay` |
| 完整地址 | `https://{已备案域名}/api/callback/...`（**含 context-path**；nginx 的 `location /api/` 已覆盖，无需另加 location） |
| **鉴权** | **公开**（鉴权列写「公开」，与 `/v/auth/login/*` 同一类）。**安全性完全由验签保证，不是由登录态**。⚠️ **机制要说准**：`SaTokenConfigure` 的拦截器其实注册在 `/**` 上，只是<b>处理器内部</b>只对 `/v/**` `/a/**` `/e/**` 三段做 `SaRouter.match(...).check(...)`，别的路径落不到任何一条 `check` 上、于是原样放行。**区别是要命的**：哪天有人在处理器里加一条 `SaRouter.match("/**")` 的全局规则，webhook 会当场变成 401 而且没有任何征兆——所以这件事由用例钉住（回调必须免登录可达），不靠这段描述 |
| 验签与解密 | **必须在领域模块的 service 内**（D1 规矩 2）；api 控制器只做「读原始报文 + 转发」 |
| 幂等 | **必须**。三方回调会重复投递、乱序投递；用例须覆盖重复 / 乱序 / **金额不符** |
| 返回 | 按各三方要求的确认报文格式，**不套 `Result`** |

| Method | URL | 状态 · 说明 | 鉴权 |
|---|---|---|---|
| POST | /callback/trade/wechat/pay | **✅ trade 批** · 微信支付结果通知（APIv3 验签 + 解密在 trade 的 service 内，控制器只转发） | 公开（靠验签，非登录态） |
| POST | /callback/trade/wechat/refund | **✅ trade 批** · 微信退款结果通知 | 公开（靠验签，非登录态） |
| POST | /callback/logistics/kuaidi100 | **✅ 物流推送批** · 快递100 订阅推送（表单 `param` / `sign`，地址带 `?sid=运单id`；`MD5(param + salt)` 验签，**每张运单一个 salt**；单号须与运单对得上；乱序到达的旧推送不覆盖新快照；应答按快递100 格式 `result=true/false`、不套 `Result`） | 公开（靠验签，非登录态） |

---

## 积分商城 —— 志愿者端 `/v/donate`（商城批 / 卷批 / 商城快递批）

| Method | URL | 状态 · 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/donate/goods | 商品列表（仅已上架且未隐藏），`?keyword=&page=&size=` | 需登录 |
| GET | /v/donate/goods/{id} | 商品详情（含规格、赞助方快照、评价摘要） | 需登录 |
| GET | /v/donate/goods/{id}/reviews | 该商品的全部评价 | 需登录 |
| GET | /v/donate/orders | 我的兑换 | 需登录 |
| POST | /v/donate/orders | 下单兑换（**下单即扣分**，见 V3规划 D4/D5/D7(b)）；卷批起可带 `couponGrantId`（**一单一卷**；商品要求指定卷时必填；兑换卷全额抵扣、实付 0 不记流水）；**✅ 商城快递批**起可带 `deliveryType`（1 自提默认 / 2 快递）、快递时必填 `shippingPayType`（1 现金 / 2 积分抵扣）与 `recvName`/`recvPhone`/`recvAddress`（**自提带了这些报错、不静默清空**）；快递费与汇率**下单时快照**，积分抵扣的并进同一笔 EXCHANGE 流水；**有现金要付的（商品现金部分或现金快递费）落 status=5 待支付**，积分库存卷照样占住；**微信支付未开通时需要现金的单当场拒绝** | 需登录 |
| GET | /v/donate/orders/{id} | 我的兑换详情（含取货码） | 需登录 |
| DELETE | /v/donate/orders/{id} | 取消兑换（**退分 + 还库存 + 还卷**，同事务，D6）；待支付的单**先关交易单、关不掉（已付）就不取消**；**已付款的单志愿者不能自己取消**（须协会驳回、原路退款） | 需登录 |
| POST | /v/donate/orders/{id}/pay | **✅ 商城快递批** · 发起付款（仅待支付；body `{code}` 为 `wx.login` 临时 code，**服务端现换 openid、不收客户端报的 openid**；复用活的交易单；交易单过期时刻不晚于兑换单付款截止；与取消持同一把志愿者锁）→ 返回唤起小程序支付的参数 | 需登录 |
| POST | /v/donate/orders/{id}/receive | **✅ 商城快递批** · 确认收货（仅本人、快递且已发货的单 → 已签收；发货满 `auto-receive-days` 天未确认的系统自动确认） | 需登录 |
| POST | /v/donate/orders/{id}/reviews | 评价（**须真兑换过**——资格闸门，比照 `submitReview`） | 需登录 |
| GET | /v/donate/reviews/mine | 我的评价 | 需登录 |
| GET | /v/donate/exchange-rules | 兑换规则（文字 + 图片，Row 8 C）；**单行、无版本**，未填写时返回空内容而非报错 | 需登录 |
| GET | /v/donate/exchange-records | 全部兑换记录（**Row 8 C 要求「全部人的」**，隐私待确认 ⑯ 相邻项） | 需登录 |
| GET | /v/donate/coupons/mine | 我的卷（`?status=` 0可用 / 1已使用 / 2已作废 / 3已过期——**3 为按时间现算的派生态，不落库**） | 需登录 |
| GET | /v/donate/coupons/usable | 这件规格我此刻能用的卷（`?specId=`，先到期的在前；**适用判定与下单同一处实现**） | 需登录 |
| GET | /v/donate/verifiers/me | 我是不是核销员（小程序据此显示「扫一扫核销」入口，**仅 UX**，核销接口另有兜底） | 需登录 |
| POST | /v/donate/orders/verify | 核销员扫码核销（Row 8 F「企业核销员可以在前端用扫一扫功能核销」；**按码不按 id**、CAS 一次性、**不能核销自己的单**） | 需登录（须为有效核销员，服务层判定） |

## 积分商城 —— 管理端 `/a/donate`

| Method | URL | 状态 · 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/donate/goods | 商品列表（含未上架 / 已隐藏） | 需登录（donate:goods **或** donate:goods-audit） |
| GET | /a/donate/goods/{id} | 商品详情（不限状态，含审核痕迹） | 需登录（donate:goods **或** donate:goods-audit） |
| POST | /a/donate/goods | 新增商品（落草稿） | 需登录（donate:goods） |
| PUT | /a/donate/goods/{id} | 修改商品（**已过审的改完退回待审**，D7(a)） | 需登录（donate:goods） |
| PATCH | /a/donate/goods/{id}/display | 排序 / 隐藏（**纯展示，不触发重审**，D7(a)） | 需登录（donate:goods） |
| DELETE | /a/donate/goods/{id} | 删除商品 | 需登录（donate:goods） |
| POST | /a/donate/goods/{id}/submit | 提交审核 | 需登录（donate:goods） |
| POST | /a/donate/goods/{id}/approve | 审核通过 | 需登录（donate:goods-audit） |
| POST | /a/donate/goods/{id}/reject | 审核驳回 | 需登录（donate:goods-audit） |
| GET | /a/donate/exchange-rules | 兑换规则当前值（与志愿者端同一份，无内部字段） | 需登录（donate:goods） |
| PUT | /a/donate/exchange-rules | 保存兑换规则（**覆盖式、无版本**；图片走 `/a/files/upload?dir=exchange-rule`） | 需登录（donate:goods） |
| GET | /a/donate/goods/{id}/reviews | 某商品的评价（仅正常项；**控制台 V3 批补**：此前只有志愿者端能列评价，后台下架入口够不着） | 需登录（donate:goods 或 donate:goods-audit） |
| DELETE | /a/donate/reviews/{id} | 下架不当评价（逻辑删除，**不新增权限点**，同活动留言下架） | 需登录（donate:goods） |
| GET | /a/donate/orders | 兑换单列表（`?keyword=` 订单号 / 商品名 / 兑换人姓名或手机号，Row 8 F） | 需登录（donate:order **或** donate:order-audit） |
| POST | /a/donate/orders/{id}/approve | 兑换审核通过（自提：生成取货码并快照自提点；**快递：进入待发货、没有取货码**） | 需登录（donate:order-audit） |
| POST | /a/donate/orders/{id}/reject | 兑换审核驳回（**退分 + 还库存 + 还卷**）；待支付的单先关交易单；**已付款的单在驳回提交之后原路退款**，退款发起失败记在单上（`cashRefundError`，到收付页按 `tradeOrderId` 重试） | 需登录（donate:order-audit） |
| POST | /a/donate/orders/verify | 现场核销取货码（**按码不按 id**——扫码扫出来的是码；**CAS，一次性**，返回该发什么） | 需登录（donate:verify） |
| POST | /a/donate/orders/{id}/ship | **✅ 商城快递批** · 快递单登记发货（仅快递且待发货；body 同捐书退回 `{expressCode, expressNo}`，单号去空白转大写；CAS 一次性；**权限与核销同一个点**——都是「把东西交出去」） | 需登录（donate:verify） |
| GET | /a/donate/coupons | 卷列表（`?keyword=&status=`；带已发放 / 已使用张数） | 需登录（donate:coupon） |
| GET | /a/donate/coupons/{id} | 卷详情 | 需登录（donate:coupon） |
| POST | /a/donate/coupons | 新建卷（默认启用；兑换卷必须指定商品且**全额抵扣**，满减卷门槛不得低于抵扣——**填了不该填的报错、不静默清空**） | 需登录（donate:coupon） |
| PUT | /a/donate/coupons/{id} | 修改卷（**只影响之后发出的卷**——已发出的卷有全套条款快照） | 需登录（donate:coupon） |
| PATCH | /a/donate/coupons/{id}/status | 启用 / 停用（`?status=1/0`；**停用只挡新发放**，已发出的照常可用；**无删除入口**：商品可能以 require_coupon_id 引用它） | 需登录（donate:coupon） |
| POST | /a/donate/coupons/{id}/grants | 发卷 / 批量发卷（按 `volunteerIds` 或 `phones`；**`requestId` 必填保幂等**；逐类回报新发 / 已发过 / 查不到的手机号 / 不可发放的人；单次上限 1000 人） | 需登录（donate:coupon） |
| GET | /a/donate/coupons/{id}/grants | 某张卷的发放记录（`?status=` 0可用 / 1已使用 / 2已作废 / 3已过期） | 需登录（donate:coupon） |
| POST | /a/donate/coupon-grants/{id}/revoke | 作废一张已发出的卷（**仅未使用的可作废**，原因必填） | 需登录（donate:coupon） |
| GET | /a/donate/verifiers | 核销员列表 | 需登录（donate:verify） |
| POST | /a/donate/verifiers | 指派核销员（须已实名且账号正常；**V3 由后台指派，企业自助留 V4**）。骨架原写 `PUT` 整表替换，落地改为逐个指派——两名管理员同时加人时整表替换会互相覆盖 | 需登录（donate:verify） |
| DELETE | /a/donate/verifiers/{id} | 撤销核销员资格（逻辑删除，之后可重新指派） | 需登录（donate:verify） |

## 公益捐书与物资流转 —— `/v/donate` · `/a/donate`（捐书批）

> ⚠️ 扫码类端点**一次一个动作**，不做批量表单——前端将来是手机网页（Row 17 F），
> 设计成批量会让那一侧重来。

| Method | URL | 状态 · 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/donate/book-campaigns | 捐书活动列表（已发布与已结束；含「本次活动数据」；未开始 / 进行中 / 已结束**按时间现算**） | 需登录 |
| GET | /v/donate/book-campaigns/{id} | 活动详情（收件人 = 当前登录人姓名，地址 = 后台预留地址 + 姓名——Row 17 原文格式） | 需登录 |
| POST | /v/donate/book-campaigns/{id}/shipments | 报名并登记寄出（物资 + 快递公司 + 单号**一次提交**；须已实名；**同一快递单号只登记一次**，取消后释放） | 需登录 |
| GET | /v/donate/shipments/mine | 我的运单（含物资明细与流转轨迹，Row 38 运单管理） | 需登录 |
| GET | /v/donate/shipments/{id} | 我的运单详情（不是本人的与不存在同一句话） | 需登录 |
| DELETE | /v/donate/shipments/{id} | 取消寄送（仅机构确认到货之前） | 需登录 |
| POST | /v/donate/shipments/{id}/return-address | 提交不合格物资的退回收件信息（Row 17 D；电话**密文**存储；寄回之前可改） | 需登录 |
| GET | /v/donate/shipments/{id}/track | 物流轨迹（快递100 **快照**；未开通时 `available=false`；查询失败返回旧快照并标 `stale`；**订阅中的运单直接返回推送保持的快照、不再按次查询**） | 需登录 |
| GET | /v/donate/items/mine | 我的捐书记录（Row 38：编号、名称、编码、受捐学校、审核状态、物资类型、借阅次数） | 需登录 |
| GET | /v/donate/barcode-catalog/{barcode} | 按商品条码查条码库（Row 17 D；**查不到返回 null**——让捐赠人手填，不是错误） | 需登录 |
| GET | /v/donate/express-companies | 可选快递公司（快递100 编码；「其他」可登记但查不了轨迹） | 需登录 |
| GET | /a/donate/book-campaigns | 捐书活动列表（`?keyword=&status=`，含本次活动数据） | 需登录（donate:item） |
| GET | /a/donate/book-campaigns/{id} | 捐书活动详情 | 需登录（donate:item） |
| POST | /a/donate/book-campaigns | 新建（落草稿） | 需登录（donate:item） |
| PUT | /a/donate/book-campaigns/{id} | 修改 | 需登录（donate:item） |
| POST | /a/donate/book-campaigns/{id}/publish | 发布（**须先填收件电话与地址**，条件写进 UPDATE 的 WHERE） | 需登录（donate:item） |
| POST | /a/donate/book-campaigns/{id}/end | 手动结束（到点不必调，展示状态按时间自动变） | 需登录（donate:item） |
| DELETE | /a/donate/book-campaigns/{id} | 删除（**仅草稿**；已发布的可能已有人寄出） | 需登录（donate:item） |
| GET | /a/donate/recipient-orgs | 受赠单位列表（清单 ⑤ 默认的主数据表） | 需登录（donate:item） |
| POST | /a/donate/recipient-orgs | 新建受赠单位（未删行中名称唯一） | 需登录（donate:item） |
| PUT | /a/donate/recipient-orgs/{id} | 修改受赠单位 | 需登录（donate:item） |
| DELETE | /a/donate/recipient-orgs/{id} | 删除受赠单位（已送达记录存名称快照，不受影响） | 需登录（donate:item） |
| GET | /a/donate/barcode-catalog | 商品条码库列表（`?keyword=` 精确条码或模糊名称） | 需登录（donate:item） |
| POST | /a/donate/barcode-catalog | 新增条码库条目 | 需登录（donate:item） |
| PUT | /a/donate/barcode-catalog/{id} | 修改条码库条目 | 需登录（donate:item） |
| DELETE | /a/donate/barcode-catalog/{id} | 删除条码库条目 | 需登录（donate:item） |
| GET | /a/donate/shipments | 运单列表（`?campaignId=&status=&returnStatus=&expressNo=&donorName=`）；**捐款批起**加 `bizType`（不传＝1 捐书活动，保持原行为；2 微心愿 / 3 众筹捐物）与 `bizId`（对应的活动 / 认领 / 众筹项目 id，与 `campaignId` 同给时以它为准） | 需登录（donate:item） |
| GET | /a/donate/shipments/{id} | 运单详情（含物资、轨迹、退回收件信息明文） | 需登录（donate:item） |
| POST | /a/donate/shipments/{id}/arrive | 扫码确认到货（Row 17 第 5 步；CAS 一次性；同时移出物流轮询集合） | 需登录（donate:item） |
| POST | /a/donate/shipments/{id}/check | 核对捐赠单据（Row 17 第 6 步；逐件判定，**须恰好覆盖全部待核对物资**，不合格须写原因） | 需登录（donate:item） |
| POST | /a/donate/shipments/{id}/return | 登记不合格物资寄回（快递公司 + 单号）。骨架原写 `POST /a/donate/items/{id}/return`，落地改为**按运单退**——不合格的几件本来就装在一个包裹里寄回去 | 需登录（donate:item） |
| POST | /a/donate/shipments/{id}/items | 单独添加物资（Row 17「后台也可单独添加」；仅已到货 / 已核对的包裹，与核对串行化） | 需登录（donate:item） |
| GET | /a/donate/shipments/{id}/track | 运单物流轨迹（`?refresh=true` 强制重查，仍受终态约束；订阅中的运单也可强制重查——推送卡住时的手动出口） | 需登录（donate:item） |
| POST | /a/donate/shipments/{id}/subscribe | **✅ 物流推送批** · 重新订阅物流推送（仅「被中止 / 已放弃」且仍在途的运单；清零计数、**沿用原 salt**，由定时任务下一轮发起——订阅是付费项） | 需登录（donate:item） |
| DELETE | /a/donate/items/{id} | 单独减少物资（已装箱 / 已送达 / 已寄回的不能删；删掉最后一件不合格的，退回流程随之撤销） | 需登录（donate:item） |
| POST | /a/donate/items/{id}/barcode | 生成物品专属码 HDI… 并返回标签（**仅核对合格**；幂等——已有码返回原码供重印） | 需登录（donate:item） |
| GET | /a/donate/items | 10 维搜索（箱码 / 专属码 / 物品条码 / 捐赠人名字·电话·单位 / 物资名称·类型 / 快递单号 / 目前进度，Row 17 F） | 需登录（donate:item） |
| GET | /a/donate/items/export | 批量导出（每物资一行，列照 Row 17 F 原文；上限 20000 行，超了报错不静默截断） | 需登录（donate:item-export） |
| POST | /a/donate/boxes | 新建箱子（生成箱码 HDB… 与条码图；一只箱子只装同一个活动的物资） | 需登录（donate:item） |
| GET | /a/donate/boxes | 箱子列表（`?campaignId=&status=`） | 需登录（donate:item） |
| GET | /a/donate/boxes/{id} | 箱子详情（含箱码条码图与箱内物资） | 需登录（donate:item） |
| POST | /a/donate/boxes/{id}/pack | 扫物品专属码装箱（**一次一件**；`UPDATE ... JOIN` 单语句 CAS） | 需登录（donate:item） |
| POST | /a/donate/boxes/{id}/unpack | 扫物品专属码出箱（仅装箱中的箱子） | 需登录（donate:item） |
| POST | /a/donate/boxes/{id}/deliver | 送达受赠单位（Row 17 第 10 步；空箱不能送达；单位名快照进物资） | 需登录（donate:item） |
| GET | /a/donate/scan | 扫码识别（`?code=`：物品专属码 / 箱码 / 快递单号 / 商品条码）——扫码页先问「这是什么」再做动作 | 需登录（donate:item） |

## 圆梦微心愿 —— `/v/donate` · `/a/donate`（微心愿批）

| Method | URL | 状态 · 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/donate/wishes | **✅ 微心愿批** · 心愿池 / 已认领 / 已实现（`?tab=0/1/2&keyword=`；**姓名与学校打 `*`**，认领人除外） | 需登录 **+ 已验手机号**（洞4） |
| GET | /v/donate/wishes/mine | **✅ 微心愿批** · 微心愿中心（Row 35：历次认领 + 寄出的包裹、物资明细与流转轨迹） | 需登录 |
| GET | /v/donate/wishes/{id} | **✅ 微心愿批** · 心愿详情（**认领后才返回全量资料 + 物资接收地址**，授权判定在服务端） | 需登录 **+ 已验手机号** |
| POST | /v/donate/wishes/{id}/claim | **✅ 微心愿批** · 认领（**须实名志愿者**；心愿行 CAS + 生成列唯一键占位） | 需登录 |
| DELETE | /v/donate/wishes/{id}/claim | **✅ 微心愿批** · 取消认领（**释放占位**；寄出的物资还「活着」时拒绝） | 需登录 |
| POST | /v/donate/wishes/{id}/shipments | **✅ 微心愿批** · 为认领的心愿登记寄出物资（复用捐书的运单 / 物资 / 轨迹，`biz_type=2`、`biz_id=认领 id`） | 需登录 |
| GET | /a/donate/wishes | **✅ 微心愿批** · 后台列表（`?status=&keyword=` 标题 / 编号 / 上报单位；资料明文 + 当前认领人） | 需登录（donate:wish） |
| POST | /a/donate/wishes | **✅ 微心愿批** · 单独上传一个心愿（编号留空由系统生成 `HDW…`） | 需登录（donate:wish） |
| GET | /a/donate/wishes/{id} | **✅ 微心愿批** · 后台详情（资料全文 + 历次认领 + 认领人电话 + 包裹与轨迹） | 需登录（donate:wish） |
| PUT | /a/donate/wishes/{id} | **✅ 微心愿批** · 修改资料（**仅待认领 / 已下架**——认领之后再改等于换了一个心愿给他） | 需登录（donate:wish） |
| POST | /a/donate/wishes/import | **✅ 微心愿批** · 批量导入心愿（xlsx；**全成或全不成**，失败逐行给出行号与原因） | 需登录（donate:wish） |
| GET | /a/donate/wishes/import-template | **✅ 微心愿批** · 下载导入模板（表头即列名） | 需登录（donate:wish） |
| GET | /a/donate/wishes/export | **✅ 微心愿批** · 批量下载（含未成年人资料；上限 20000 行，超了报错不静默截断） | 需登录（donate:wish） |
| POST | /a/donate/wishes/{id}/take-down | **✅ 微心愿批** · 下架（仅待认领——已认领的要先撤销认领） | 需登录（donate:wish） |
| POST | /a/donate/wishes/{id}/restore | **✅ 微心愿批** · 重新上架（回到心愿池） | 需登录（donate:wish） |
| POST | /a/donate/wishes/{id}/revoke-claim | **✅ 微心愿批** · 后台取消认领（原因必填、认领人收到站内提示；物资还在流转中则拒绝） | 需登录（donate:wish） |
| POST | /a/donate/wishes/{id}/realize | **✅ 微心愿批** · 心愿实现：合格物资发放 + 上传发放图片 1~9 张（认领人收到站内提示） | 需登录（donate:wish） |

> **物资到货之后走捐书批的通用接口**，不另开一套：`/a/donate/scan`（扫快递单号）、`/a/donate/shipments/{id}/arrive`、`/check`、
> `/a/donate/items/{id}/barcode`（面单上带**心愿编号、受捐学生、上报单位**，Row 12 G）；认领人看自己的包裹同样走 `/v/donate/shipments/…`。
> **排行榜第四板块自本批起放行**：`GET /v/honor/rankings?rankType=4`（按「已实现」的心愿数算，见 honor）。

## 助学助困与众筹 —— `/v/donate` · `/a/donate`（结对批 / 捐款批）

| Method | URL | 状态 · 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/donate/pair-projects | **✅ 结对批** · 结对项目（`?tab=` 1助学 / 2助困 / 3助残 / 4结对成功） | 需登录 |
| GET | /v/donate/pair-projects/{id} | **✅ 结对批** · 项目详情（含我的登记与「此刻能不能登记」） | 需登录 |
| GET | /v/donate/pair-projects/{id}/letters | **✅ 结对批** · 受助方来信（**公开信 + 写给我的那些**，过滤在服务端） | 需登录 |
| POST | /v/donate/pair-projects/{id}/pairs | **✅ 结对批** · 结对登记（指定金额 / 全款；**须已实名**；本批不含支付） | 需登录 |
| DELETE | /v/donate/pair-projects/{id}/pairs | **✅ 结对批** · 撤回登记（仅「待确认」；已成立的须协会取消）；**捐款批起**：付过款的不能撤回（须协会取消、原路退款），有待支付的先关交易单 | 需登录 |
| GET | /v/donate/crowdfunds | **✅ 结对批** · 众筹项目（`?tab=` 0全部 / 1进行中 / 2已结束） | 需登录 |
| GET | /v/donate/crowdfunds/{id} | **✅ 结对批** · 项目详情与进度（**捐款批起**已筹金额＝已到账捐款之和、捐款人数＝已到账捐款去重现算；另带 `acceptMoney` / `acceptGoods` 与接受捐物时的 `goodsNeeded` + 收件人 / 电话 / 地址——协会的收件信息，捐物的人照着寄） | 需登录 |
| POST | /v/donate/pair-projects/{id}/donations | **✅ 捐款批** · 结对捐款（**不收金额，付「认捐额 − 已付」**；须已登记结对；一条结对至多一笔待支付；body 含 `wx.login` 的 `code` 与可选发票字段；返回捐款记录 + 唤起支付参数；**微信支付未开通时当场拒绝、不落库**） | 需登录 |
| GET | /v/donate/pair-projects/{id}/donations | **✅ 捐款批** · 结对项目捐赠记录（Row 10「项目捐赠记录」；只列已到账，**姓名打码**） | 需登录 |
| POST | /v/donate/crowdfunds/{id}/donations | **✅ 捐款批** · 众筹捐款（金额自填，最多两位小数、单笔上限可配；项目须在募集中且接受捐款；`need_invoice` 为真时抬头必填、为假时不许填；返回捐款记录 + 唤起支付参数；**未开通时当场拒绝、不落库**） | 需登录 |
| GET | /v/donate/crowdfunds/{id}/donations | **✅ 捐款批** · 众筹项目捐赠记录（只列已到账，**姓名打码**） | 需登录 |
| GET | /v/donate/donations/{id} | **✅ 捐款批** · 我的一笔捐款详情（别人的与不存在同一句话） | 需登录 |
| POST | /v/donate/donations/{id}/pay | **✅ 捐款批** · 重新发起付款（仅待支付、未过付款截止；复用活的交易单；与取消持同一把锁） | 需登录 |
| DELETE | /v/donate/donations/{id} | **✅ 捐款批** · 取消待支付的捐款（**先关交易单，关不掉＝刚付款，放弃取消**） | 需登录 |
| POST | /v/donate/crowdfunds/{id}/goods-donations | **✅ 捐款批** · 众筹捐物（body 与捐书登记寄出相同：快递公司 + 单号 + 物资清单；须已实名、项目在募集中且接受捐物；落运单 `biz_type=3`、`biz_id`=项目 id，之后的到货 / 核对 / 专属码 / 装箱 / 送达 / 轨迹全走捐书那一套；**一个快递单号只登记一次**） | 需登录 |
| GET | /v/donate/donations/mine | **✅ 收尾批** · 我的捐赠记录（Row 33「众筹系统中的捐赠记录和捐物记录，每捐一次为一次记录」）：众筹的捐款与捐物合成一条时间线，`?kind=` 0全部 / 1捐款 / 2捐物；捐款按发起时间、捐物按寄出登记时间倒序，**分页在库里合并**；每行 `kind + refId`，详情分别走 `GET /v/donate/donations/{id}` 与 `GET /v/donate/shipments/{id}`（含物流轨迹）；**结对捐款不在这里**（在结对中心）、捐书与微心愿的物资各有记录页 | 需登录 |
| GET | /v/donate/pairs/mine | **✅ 结对批** · 结对中心列表（Row 34「一个项目为一个记录」）；**捐款批起**每条带 `paidAmount` | 需登录 |
| GET | /v/donate/pairs/mine/{id} | **✅ 收尾批** · 结对中心详情（Row 34「进去后会有详细的捐赠信息，需要预留开发票」）：这条结对 + `remainingAmount`（认捐额 − 已付，已取消为 0）+ 为它发起过的每一笔捐款（含已取消 / 已退款，带发票字段），新的在前；别人的与不存在同一句话 | 需登录 |
| GET | /a/donate/pair-projects | **✅ 结对批** · 结对项目列表（状态 / 类型 / 关键词） | 需登录（donate:project） |
| POST | /a/donate/pair-projects | **✅ 结对批** · 新建结对项目（落草稿） | 需登录（donate:project） |
| GET | /a/donate/pair-projects/{id} | **✅ 结对批** · 结对项目详情 | 需登录（donate:project） |
| PUT | /a/donate/pair-projects/{id} | **✅ 结对批** · 修改结对项目（已结束的不能改） | 需登录（donate:project） |
| DELETE | /a/donate/pair-projects/{id} | **✅ 结对批** · 删除结对项目（仅草稿且无人登记） | 需登录（donate:project） |
| POST | /a/donate/pair-projects/{id}/publish | **✅ 结对批** · 上架（草稿 → 进行中，志愿者端从此可见） | 需登录（donate:project） |
| POST | /a/donate/pair-projects/{id}/end | **✅ 结对批** · 结束项目（不再接受新的结对登记） | 需登录（donate:project） |
| GET | /a/donate/pairs | **✅ 结对批** · 结对登记列表（`?projectId=&status=`，含结对人姓名与电话） | 需登录（donate:project） |
| POST | /a/donate/pairs/{id}/establish | **✅ 结对批** · 确认结对成立（认捐额累加，**出证的触发点**） | 需登录（donate:project） |
| POST | /a/donate/pairs/{id}/cancel | **✅ 结对批** · 取消结对（原因必填；已成立的退回认捐额并撤销证书）；**捐款批起**：待支付的先关交易单（关不掉＝刚付，拒绝取消），**取消提交之后**把这条结对已到账的钱逐笔原路退回 | 需登录（donate:project） |
| GET | /a/donate/donations | **✅ 捐款批** · 捐款列表（`?bizType=2众筹/3结对&projectId=&status=&invoiceStatus=&keyword=`；keyword 试单号 / 项目名 / 捐款人姓名或手机号） | 需登录（donate:project） |
| POST | /a/donate/donations/{id}/refund | **✅ 捐款批** · 退一笔已到账的捐款（**先 CAS 并减回已筹 / 已付，提交后原路退款**；原路退款失败不回滚、原因记 `cashRefundError`；**已开票的须先作废发票**） | 需登录（donate:project **且** trade:refund） |
| POST | /a/donate/donations/{id}/invoice | **✅ 捐款批** · 登记开票（清单⑦只预留：发票在系统外开，这里记发票号；仅已到账且需要发票的） | 需登录（donate:project） |
| GET | /a/donate/pair-projects/{id}/letters | **✅ 结对批** · 来信列表（后台看全部，含写给某个结对人的） | 需登录（donate:project） |
| POST | /a/donate/pair-projects/{id}/letters | **✅ 结对批** · 录入受助方来信（不填收信人=项目公开信；指定收信人时**只能是已成立的结对**，待确认 / 已取消的拒绝） | 需登录（donate:project） |
| DELETE | /a/donate/letters/{id} | **✅ 结对批** · 删除来信 | 需登录（donate:project） |
| GET | /a/donate/crowdfunds | **✅ 结对批** · 众筹项目列表 | 需登录（donate:project） |
| POST | /a/donate/crowdfunds | **✅ 结对批** · 新建众筹项目（落草稿）；**捐款批起** body 加 `acceptMoney`（不传＝收）/ `acceptGoods`（不传＝不收）/ `goodsNeeded` / `recvName` / `recvPhone` / `recvAddress`：两个开关至少开一个，收物时收件三项必填、不收物时不许填 | 需登录（donate:project） |
| GET | /a/donate/crowdfunds/{id} | **✅ 结对批** · 众筹项目详情 | 需登录（donate:project） |
| PUT | /a/donate/crowdfunds/{id} | **✅ 结对批** · 修改众筹项目（已结束的不能改） | 需登录（donate:project） |
| DELETE | /a/donate/crowdfunds/{id} | **✅ 结对批** · 删除众筹项目（仅草稿） | 需登录（donate:project） |
| POST | /a/donate/crowdfunds/{id}/publish | **✅ 结对批** · 上架众筹项目 | 需登录（donate:project） |
| POST | /a/donate/crowdfunds/{id}/end | **✅ 结对批** · 结束众筹项目 | 需登录（donate:project） |
| POST | /a/honor/certificates/pairs/{pairRecordId} | **✅ 结对批** · 补发某条结对的捐赠证书（**幂等**；出证事件丢失时的人工救济） | 需登录（honor:certificate） |

> **捐赠证书**（Row 10「捐赠后需要自动生成证书」）：确认结对成立 → donate 发领域事件 → honor 出证
> （类型 3，幂等键 `pair:{结对登记id}`，V54 的 `uk_cert_biz_ref`）；取消已成立的结对会连带把证书软删。
> 志愿者在既有的 `GET /v/honor/certificates` 里看到它，来源字段写的是结对项目名。
> ⚠️ 触发点取《协会待确认清单-v3》⑨ 的默认「结对成立即出证」；协会若改成「钱到账才出证」，
> 挪的只是挂钩的位置（到捐款批），权益与渲染这一半不用动。

## 收付能力 —— 管理端 `/a/trade`（trade 批）

> ⚠️ **控制器在 api 模块，不在 trade 模块**（D1 规矩 1）。trade 的 service 收 `operatorId` 并硬校验非空。

| Method | URL | 状态 · 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/trade/orders | **✅ trade 批** · 交易单列表（按 bizType / 状态 / 创建时间筛选） | 需登录（trade:order） |
| GET | /a/trade/orders/{id} | **✅ trade 批** · 交易单详情（含支付 / 退款流水） | 需登录（trade:order） |
| POST | /a/trade/orders/{id}/query | **✅ trade 批** · **主动查单**（本地与微信不一致时以此为准） | 需登录（trade:order） |
| POST | /a/trade/orders/{id}/close | **✅ trade 批** · 关单（仅待支付；已支付的关掉等于把收到的钱从账上抹掉） | 需登录（trade:order） |
| POST | /a/trade/orders/{id}/refund | **✅ trade 批** · 退款（不填金额=整单退，粒度见清单 ⑭；原因必填） | 需登录（trade:refund） |
| POST | /a/trade/reconciliations | **✅ trade 批** · 手动对账（**第四道，不是唯一那道**，D2；`from`/`to` 必须显式给出；**已支付与已关闭两侧都核**，差异逐条列出，结果落库） | 需登录（trade:order） |
| GET | /a/trade/reconciliations | **✅ trade 批** · 对账记录列表（每日定时核前一天 + 手动；`onlyMismatch=true` 只看有差异的、`onlySkipped=true` 只看渠道未开通没核的；不含差异明细） | 需登录（trade:order） |
| GET | /a/trade/reconciliations/{id} | **✅ trade 批** · 对账记录详情（含逐条差异） | 需登录（trade:order） |

---

## 数据汇总（捐赠部分）—— 管理端 `/a/data`（收尾批）

| Method | Path | 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/data/donation-summary | **✅ 收尾批** · Row 79 数据汇总里属捐赠的三块：**微心愿**（发布总数 / 认领成功 / 其中已实现 / 参与人数 / 参与人次 / 收到包裹）、**结对**（发布总数 / 结对成功 / 参与人次 / 结对金额分认捐额与到账额两个数）、**捐书活动**（参加人数 / 收到包裹 / 收到课外书籍 / 学习用品 / 运动器材，与单个活动「本次活动数据」同口径；**修建书屋数恒为 null**——系统里没有这个概念）。口径收在 donate 的 `DonateStatsMapper`，data 只转发；与 `/a/data/dashboard` 分开是因为它含金额、而头部统计志愿者端也在用 | 需登录（管理端，与 `/a/data/dashboard` 同为纯聚合、不挂权限点） |

## 权限点登记（V3 新增，实施时同步 `permission` 种子与 `OrganizationRbacTest` 总数断言）

| 权限点 | 含义 | 批次 |
|---|---|---|
| `donate:goods` | 商品管理 | 商城批 |
| `donate:goods-audit` | 商品审核 | 商城批 |
| `donate:order` | 兑换单查看 | 商城批 |
| `donate:order-audit` | 兑换审核 | 商城批 |
| `donate:verify` | 现场核销 / 核销员指派 | 商城批 / 卷批 |
| `donate:coupon` | 卷管理与发放 | 卷批 |
| `donate:item` | 物资流转与扫码动作、10 维搜索 | 捐书批 |
| `donate:item-export` | **物资明细批量导出**——与 `donate:item` 分开，同 `user:list` / `user:export` 的先例：Row 17 F 的导出列含「捐赠人名字、电话、单位、志愿者码链接」，与志愿者名册是同一类数据 | 捐书批 |
| `donate:wish` | 微心愿管理 | 微心愿批 |
| `donate:project` | 结对 / 众筹项目管理 | 结对批 |
| `trade:order` | 交易单查看 / 查单 / 关单 / 对账 | trade 批 |
| `trade:refund` | 退款 | trade 批 |

> ⚠️ **每加一个都必须同步 `OrganizationRbacTest.permissionsSeeded` 的总数断言**（当前 51）——
> 那条断言就是防漏改的哨兵。V2 第 5 批合入后基线已是 **57**（多了 `honor:reward-punish-final`），**卷批（V46）后为 58**、**捐书批（V50）后为 60**、**微心愿批（V51）后为 61**、**结对批（V52）后为 62**、**trade 批（V55）后为 64**，**V3 全部落地后应为 64**（已到位）。
