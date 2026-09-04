# 接口 URL 约束文档 V3（骨架）

> **本文件是 V3（积分商城 / 捐赠域 / 平台级收付）端点的约束清单，随各批实施逐批填实。**
> V1 → V2 的全量端点仍以 [`文档/v2/url文档v2.md`](../v2/url文档v2.md) 为准，**两份并存、互不覆盖**。
>
> 🛑 **本文件当前是骨架**：下面列出的路径是**已规划、尚未实现**的，每行都带「⬜ 未实现」标记。
> 在此登记是为了让读者知道**这批路径已被占用**，避免另起冲突的命名——
> **不是**把未实现的东西混进「已实现清单」（与 `url文档v2.md` 对第 4 批的处理同一口径）。
> 某一批实现后，把该批的行去掉 ⬜ 标记并补齐鉴权列，`tools/verify_url_contract.py` 才会绿。
>
> ⚠️ **契约脚本必须先改成读多份文档**（`tools/verify_url_contract.py` 第 656 行的文档路径写死在 v2）。
> 这是**前置批**的交付项之一：不改，V3 的端点要么整片被报成「代码有而文档无」，要么根本不受检。
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
| **鉴权** | **公开**（鉴权列写「公开」，与 `/v/auth/login/*` 同一类）。Sa-Token 的拦截器只 match `/v` `/a` `/e` 三段，`/callback/**` 天然不进鉴权链——**安全性完全由验签保证，不是由登录态** |
| 验签与解密 | **必须在领域模块的 service 内**（D1 规矩 2）；api 控制器只做「读原始报文 + 转发」 |
| 幂等 | **必须**。三方回调会重复投递、乱序投递；用例须覆盖重复 / 乱序 / **金额不符** |
| 返回 | 按各三方要求的确认报文格式，**不套 `Result`** |

| Method | URL | 状态 · 说明 | 鉴权 |
|---|---|---|---|
| POST | /callback/trade/wechat/pay | **⬜ 未实现（trade 批）** · 微信支付结果通知（APIv3 验签 + 解密） | 公开（靠验签，非登录态） |
| POST | /callback/trade/wechat/refund | **⬜ 未实现（trade 批）** · 微信退款结果通知 | 公开（靠验签，非登录态） |
| POST | /callback/logistics/kuaidi100 | **⬜ 未实现（物流推送批）** · 快递100 订阅推送 | 公开（靠验签，非登录态） |

---

## 积分商城 —— 志愿者端 `/v/donate`（商城批 / 卷批 / 商城快递批）

| Method | URL | 状态 · 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/donate/goods | 商品列表（仅已上架且未隐藏），`?keyword=&page=&size=` | 需登录 |
| GET | /v/donate/goods/{id} | 商品详情（含规格、赞助方快照、评价摘要） | 需登录 |
| GET | /v/donate/goods/{id}/reviews | 该商品的全部评价 | 需登录 |
| GET | /v/donate/orders | 我的兑换 | 需登录 |
| POST | /v/donate/orders | 下单兑换（**下单即扣分**，见 V3规划 D4/D5/D7(b)） | 需登录 |
| GET | /v/donate/orders/{id} | 我的兑换详情（含取货码） | 需登录 |
| DELETE | /v/donate/orders/{id} | 取消兑换（**退分 + 还库存**，D6） | 需登录 |
| POST | /v/donate/orders/{id}/reviews | 评价（**须真兑换过**——资格闸门，比照 `submitReview`） | 需登录 |
| GET | /v/donate/reviews/mine | 我的评价 | 需登录 |
| GET | /v/donate/exchange-rules | 兑换规则（文字 + 图片，Row 8 C）；**单行、无版本**，未填写时返回空内容而非报错 | 需登录 |
| GET | /v/donate/exchange-records | 全部兑换记录（**Row 8 C 要求「全部人的」**，隐私待确认 ⑯ 相邻项） | 需登录 |
| GET | /v/donate/coupons/mine | **⬜ 卷批** · 我的卷 | 需登录 |

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
| DELETE | /a/donate/reviews/{id} | 下架不当评价（逻辑删除，**不新增权限点**，同活动留言下架） | 需登录（donate:goods） |
| GET | /a/donate/orders | 兑换单列表（`?keyword=` 订单号 / 商品名 / 兑换人姓名或手机号，Row 8 F） | 需登录（donate:order **或** donate:order-audit） |
| POST | /a/donate/orders/{id}/approve | 兑换审核通过 | 需登录（donate:order-audit） |
| POST | /a/donate/orders/{id}/reject | 兑换审核驳回（**退分 + 还库存**） | 需登录（donate:order-audit） |
| POST | /a/donate/orders/verify | 现场核销取货码（**按码不按 id**——扫码扫出来的是码；**CAS，一次性**，返回该发什么） | 需登录（donate:verify） |
| GET | /a/donate/coupons | **⬜ 卷批** · 卷列表 | 需登录（donate:coupon） |
| POST | /a/donate/coupons | **⬜ 卷批** · 新建卷 | 需登录（donate:coupon） |
| POST | /a/donate/coupons/{id}/grants | **⬜ 卷批** · 发卷 / 批量发卷 | 需登录（donate:coupon） |
| GET | /a/donate/verifiers | **⬜ 卷批** · 核销员列表 | 需登录（donate:verify） |
| PUT | /a/donate/verifiers | **⬜ 卷批** · 指派核销员（**V3 由后台指派，企业自助留 V4**） | 需登录（donate:verify） |

## 公益捐书与物资流转 —— `/v/donate` · `/a/donate`（捐书批）

> ⚠️ 扫码类端点**一次一个动作**，不做批量表单——前端将来是手机网页（Row 17 F），
> 设计成批量会让那一侧重来。

| Method | URL | 状态 · 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/donate/book-campaigns | **⬜ 捐书批** · 捐书活动列表（含本次活动数据） | 需登录 |
| POST | /v/donate/book-campaigns/{id}/shipments | **⬜ 捐书批** · 报名并登记寄出（快递公司 + 单号） | 需登录 |
| GET | /v/donate/shipments/mine | **⬜ 捐书批** · 我的运单（含物资明细与轨迹） | 需登录 |
| GET | /v/donate/items/mine | **⬜ 捐书批** · 我的捐书记录（Row 38） | 需登录 |
| POST | /a/donate/shipments/{id}/arrive | **⬜ 捐书批** · 扫码确认到货 | 需登录（donate:item） |
| POST | /a/donate/shipments/{id}/check | **⬜ 捐书批** · 扫码核对捐赠单据 | 需登录（donate:item） |
| POST | /a/donate/items/{id}/barcode | **⬜ 捐书批** · 生成物品专属条码 | 需登录（donate:item） |
| POST | /a/donate/boxes/{id}/pack | **⬜ 捐书批** · 扫码装箱 | 需登录（donate:item） |
| POST | /a/donate/boxes/{id}/deliver | **⬜ 捐书批** · 扫码确认送达受赠单位 | 需登录（donate:item） |
| POST | /a/donate/items/{id}/return | **⬜ 捐书批** · 不合格退回（登记退回单号） | 需登录（donate:item） |
| GET | /a/donate/items | **⬜ 捐书批** · 10 维搜索（箱码 / 条码 / 捐赠人 / 物资 / 单号 / 进度…，Row 17 F） | 需登录（donate:item） |
| GET | /a/donate/items/export | **⬜ 捐书批** · 批量导出（每物资一行） | 需登录（donate:item-export） |

## 圆梦微心愿 —— `/v/donate` · `/a/donate`（微心愿批）

| Method | URL | 状态 · 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/donate/wishes | **⬜ 微心愿批** · 心愿池 / 已认领 / 已实现（**关键信息打 `*`**） | 需登录 **+ 已验手机号**（洞4） |
| GET | /v/donate/wishes/{id} | **⬜ 微心愿批** · 心愿详情（**认领后才返回全量资料**，授权判定在服务端） | 需登录 **+ 已验手机号** |
| POST | /v/donate/wishes/{id}/claim | **⬜ 微心愿批** · 认领（**须实名志愿者**；生成列唯一键占位） | 需登录 |
| DELETE | /v/donate/wishes/{id}/claim | **⬜ 微心愿批** · 取消认领（**释放占位**） | 需登录 |
| GET | /v/donate/wishes/mine | **⬜ 微心愿批** · 微心愿中心（Row 35，认领记录 + 轨迹） | 需登录 |
| POST | /a/donate/wishes/import | **⬜ 微心愿批** · 批量导入心愿 | 需登录（donate:wish） |
| GET | /a/donate/wishes | **⬜ 微心愿批** · 后台列表（搜索框 + 下拉框） | 需登录（donate:wish） |
| POST | /a/donate/wishes/{id}/revoke-claim | **⬜ 微心愿批** · 后台取消认领 | 需登录（donate:wish） |

## 助学助困与众筹 —— `/v/donate` · `/a/donate`（结对批 / 捐款批）

| Method | URL | 状态 · 说明 | 鉴权 |
|---|---|---|---|
| GET | /v/donate/pair-projects | **⬜ 结对批** · 结对项目（助学 / 助困 / 助残 / 已结对） | 需登录 |
| GET | /v/donate/pair-projects/{id} | **⬜ 结对批** · 项目详情（含捐赠记录） | 需登录 |
| GET | /v/donate/pair-projects/{id}/letters | **⬜ 结对批** · 受助方来信（图文） | 需登录 |
| GET | /v/donate/crowdfunds | **⬜ 结对批** · 众筹项目（全部 / 进行中 / 已结束） | 需登录 |
| GET | /v/donate/crowdfunds/{id} | **⬜ 结对批** · 项目详情与进度 | 需登录 |
| POST | /v/donate/pair-projects/{id}/donations | **⬜ 捐款批** · 结对捐款（**下单 → trade**） | 需登录 |
| POST | /v/donate/crowdfunds/{id}/donations | **⬜ 捐款批** · 众筹捐款（**下单 → trade**） | 需登录 |
| POST | /v/donate/crowdfunds/{id}/goods-donations | **⬜ 捐款批** · 众筹捐物（走物资流转） | 需登录 |
| GET | /v/donate/donations/mine | **⬜ 收尾批** · 捐赠记录（Row 33） | 需登录 |
| GET | /v/donate/pairs/mine | **⬜ 收尾批** · 结对中心（Row 34） | 需登录 |
| GET/POST/PUT/DELETE | /a/donate/pair-projects · /crowdfunds | **⬜ 结对批** · 项目后台管理（上传 / 下架 / 审核 / 结束 / 批量） | 需登录（donate:project） |

## 收付能力 —— 管理端 `/a/trade`（trade 批）

> ⚠️ **控制器在 api 模块，不在 trade 模块**（D1 规矩 1）。trade 的 service 收 `operatorId` 并硬校验非空。

| Method | URL | 状态 · 说明 | 鉴权 |
|---|---|---|---|
| GET | /a/trade/orders | **⬜ trade 批** · 交易单列表（按 bizType / 状态 / 时间筛选） | 需登录（trade:order） |
| GET | /a/trade/orders/{id} | **⬜ trade 批** · 交易单详情（含支付 / 退款流水） | 需登录（trade:order） |
| POST | /a/trade/orders/{id}/query | **⬜ trade 批** · **主动查单**（本地与微信不一致时以此为准） | 需登录（trade:order） |
| POST | /a/trade/orders/{id}/close | **⬜ trade 批** · 关单 | 需登录（trade:order） |
| POST | /a/trade/orders/{id}/refund | **⬜ trade 批** · 退款（**默认整单**，粒度见清单 ⑭） | 需登录（trade:refund） |
| GET | /a/trade/reconciliations | **⬜ trade 批** · 对账结果（**第四道，不是唯一那道**，D2） | 需登录（trade:order） |

---

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
> 那条断言就是防漏改的哨兵。V3 全部落地后应为 **63**。
