# trade 模块 · 收付能力

> 代码：`代码/hengde-volunteer-trade/`。本页由根 `CLAUDE.md` 的「已完成」表下沉而来（2026-10-17）；
> 上半部分是**现行要点**，下半部分是按批次的原始记录（为什么这样做、撞过什么）。两者冲突时以上半部分与代码为准，并顺手修正下半部分。

## 职责

微信支付 APIv3 的交易单、支付流水、退款、对账。⚠️ **没有商户号，从未真实调用过**——用例走的是假渠道。

## 现行要点

- trade 只依赖 common、不放 controller；控制器在 api，service 收 `operatorId`。
- 回调、主动查单、分钟级扫描三路汇到 `applyPaidResult`：金额比对、流水幂等、CAS 待支付→已支付。
- `TradePaidEvent` 在事务提交、连接归还之后才发；订阅方必须 `fallbackExecution = true` 并自带补偿（它只发一次）。
- 唯一键是生成列 `uk_active_biz`（关闭 / 退款即释放）。
- 网络调用一律在事务外；渠道未开通时先拒绝、不落库；对账每次都落库（跳过的也落）。

## 按批次的记录

**V3 trade 批已完成（V55，2026-09-17）——平台级收付能力，本地状态机层面**。⚠️ **没有商户资质与备案域名，微信那一段一次真实调用都没有发生过**：`WechatPaymentGateway`（wx-java-pay APIv3：`createOrderV3`/`queryOrderV3`/`closeOrderV3`/`refundV3`/回调验签解密）只在编译期对过方法签名，用例走的是 `FakePaymentGateway`——**别把「用例全绿」当成「支付通了」**，资质下来后第一件事是 1 分钱走一遍「下单 → 付款 → 回调 → 查单 → 退款 → 对账」。

**模块边界（V3规划 D1，两条写死的规矩）**：① **trade 不放 controller、只依赖 common**——`AdminTradeController`（`/a/trade`）与 `TradeCallbackController`（`/callback/trade/wechat/pay|refund`）都在 api，trade 的 service 收 `operatorId` 并**硬校验非空**（trade 若为取登录态去依赖 auth，日后 auth 查会员费缴纳状态就会成环）；② **验签与解密在 trade 的 `TradeCallbackService` 里**，api 控制器只「读原始报文 + 转发」，否则支付逻辑会一点点长到 api 去。**四张表**：`trade_order`（**金额一律存「分」INT**，与渠道一致；业务侧 DECIMAL 元，换算只在调用方进 trade 那一刻做一次）/ `trade_payment`（`uk_transaction` 幂等）/ `trade_refund` / `trade_reconcile_run`（对账记录）。⚠️ **唯一键不是规划简写的 `uk_biz(biz_type,biz_no)`，是生成列 `uk_active_biz`**——待支付 / 已支付占键、关闭 / 退款释放：真按简写建，订单超时关闭之后同一笔业务**再也付不了第二次**（微信要求关单后换新单号重下）。**D2「支付成功怎么回到业务域」四道缺一不可**：① 回调、主动查单、分钟级扫描（`TradeScanJob`）**三条路汇到同一个幂等方法** `TradeOrderService.applyPaidResult`——当前读锁单 → **金额比对（不符一律拒绝并 ERROR，这是验签之外防伪造的第二道）** → 插支付流水（撞 `uk_transaction` 即重放，赢家用 `FOR SHARE` 读回；同一渠道单号挂到另一张单上则报错）→ CAS 待支付→已支付 → 发 `TradePaidEvent`；④ **每日对账**（`TradeReconcileJob` 核前一天）**核已支付与已关闭两侧**，**每一次都落库、跳过的也落**（`skipped=1`）——「钱付进已关闭的单」（关单边界上付款、后台关单后渠道关单失败）在回调那一侧是「流水照记、状态不改、打 ERROR」，**只核已支付那一侧永远看不见它**，而只写 ERROR 等于一条没人读的日志；停机跨过凌晨那次执行不会自动补，按日期看缺口手动 `POST /a/trade/reconciliations` 补。**网络调用一律在事务外**（下单 / 查单 / 退款要几百毫秒到几秒，包进事务会让连接与行锁陪着等）。**渠道未开通（默认）时**：下单**先问开没开通再落库**（反过来每次「未开通」都留一张没人付的空单），明确拒绝、不伪造成功；对账如实回 `skipped`。**webhook 应答**：处理完（含重复投递）200 SUCCESS；**验签失败 / 金额不符 / 单号不认识回 500 FAIL**——回 SUCCESS 等于把可疑投递藏起来，而渠道重投正是我们要的第二次机会。⚠️ **webhook 必须读原始字节流，不能用 `@RequestBody String`**：MVC 前置的 Jackson 转换器会把 `application/json` 报文当「一个 JSON 字符串」解析，合法通知体直接 400、进不了控制器也没有日志；验签算的也正是这串原文。**退款**：事务内先占额度（`addRefunded`，**上限写在 UPDATE 的 WHERE 里**）+ 落「处理中」，渠道调用在事务外，拒绝 / 未开通即置失败并释放额度，结果回写幂等（`TradeRefundedEvent`）。⚠️ **TTL 默认值写在 `TradeProperties` 而不只写在 api 的 yaml**（默认 15 分钟；众筹 2 / 结对捐款 3 覆盖为一天——不占资源，15 分钟关单只会打断一笔捐赠）：yaml 只在 api 上下文生效，领域测试读不到，「捐款给一天」就成了只在部署里成立的话（用例当场撞出来的）。`ProductionConfigGuard` 在支付开启时要求商户号 / APIv3 密钥 / 证书序列号 / 私钥齐全且两个回调地址是 https。2 个权限点 `trade:order` / `trade:refund`（共 64）。

**`TradePaidEvent` 自商城快递批起由 donate 的 `MallPaymentListener` 消费**，捐款批起 `DonationPaymentListener` 也订阅。⚠️ **事件在 trade 事务提交、连接归还之后才发布**（捐款批压测撞出的全项目级缺陷）：原先在事务里发、订阅方 `AFTER_COMMIT` + `REQUIRES_NEW` 回写——AFTER_COMMIT 回调跑在 `afterCompletion` 里，那时 trade 事务的连接**还没还**，`REQUIRES_NEW` 再要一条，**每个付款回调同时占两条连接**；16 个并发回调把 10 条的池子卡死 30 秒，回写全部失败、连带所有接口拿不到连接。现在 `applyPaidResult` / `applyRefundResult` 在事务里只把事件备好、`execute` 返回后才发，订阅方写 `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)`——**缺了 `fallbackExecution` 监听器一次都不会被调用**（没有事务时 `@TransactionalEventListener` 默认不执行），所有付款只能等补偿任务。由 donate 的 `PaymentListenerConnectionTest` 钉住（连接池限成 1 条、单线程确定性复现，修之前两条都红）。⚠️ **它只发一次**——只有真正完成「待支付 → 已支付」迁移的那次发，重复回调 / 查单 / 扫描撞上已支付的单不会再发（javadoc 曾写成「三条路最终都会再发一次」，是错的，照那句话写订阅者会在进程崩溃时永久丢单）：**订阅方必须自带补偿**，拿自己「还在等付款」的单用 `findLatestByBiz` 去问。

**商城快递批补的三个业务入口**：`paymentEnabled()`（先问开没开通，免得让付不了的单占着库存）、`findLatestByBiz(bizType,bizNo)`（含已关闭的）、`closeByBiz(bizType,bizNo)`（业务侧关活单，**返回 false＝已付、调用方必须放弃取消**；CAS 失败后的复核是当前读），外加 `CreateOrder.expireAt`（交易单过期取 TTL 与业务截止中更早的那个）。`TradeBizEntryTest` 的关单对付款赛跑**偏置起跑并断言两种结局都出现过**——不偏置时是 30:0，「关单赢」那条分支一次都没被跑到。测试：`TradeOrderServiceTest` 8 / `TradeRefundServiceTest` 6 / `TradeCallbackAndScanTest` 7（**重复投递 / 乱序投递 / 金额不符**逐条钉住，D2 明确要求）/ `TradeReconcileTest` 9 / 压测 `TradeStressTest`（`@Tag("stress")`：同一笔支付回调 + 查单 + 扫描三路同时写回 120 次操作、每单 5 笔并发退款抢 3 笔额度 100 次操作，断言每单恰好一条流水、已退不越过订单额且等于非失败退款之和、零死锁）/ api 的 `TradeCallbackApiTest`（webhook 免登录可达 + 反向断言 `/a/**` 不带 token 必须 401，**必跑**）。五处变异均已验证必红：去掉金额比对 / 回调处理失败仍回 SUCCESS / 对账不核已关闭那一侧 / 跳过时不落库 / 去掉启动时注册管理端 StpLogic（见 api 行）。
