package com.hengde.trade.event;

/**
 * 交易单支付成功后发布的领域事件——<b>业务回写的入口</b>（商城快递费发货、众筹 / 结对捐款入账）。
 *
 * <p><b>依赖方向</b>：trade 只依赖 common，业务域（donate 等）依赖 trade，
 * 所以事件定义在 trade（发布方）、业务域订阅，与 activity → honor、donate → honor 同形。</p>
 *
 * <p>⚠️ <b>事件是进程内、不持久的，订阅者不能把它当成唯一通路</b>（V3规划 D2）：
 * 证书丢一次事件只是少一张证书，<b>支付丢一次事件是钱收了、单没发货</b>。</p>
 *
 * <p>⚠️ <b>它只发一次</b>：只有真正完成「待支付 → 已支付」迁移的那一次才发，重复回调、查单、扫描撞上
 * 已支付的单是不会再发的（此前这里写成「三条路最终都会再发一次」，那是错的——照那句话写订阅者，
 * 进程在提交之后、订阅者跑完之前挂掉一次，这张单就永远没有第二次机会）。
 * 所以<b>订阅方必须自带补偿</b>：定期拿自己「还在等付款」的单去问 trade（{@code TradeOrderService.findLatestByBiz}），
 * 已支付的照样推进——商城的 {@code MallOrderSyncJob} 就是这么做的。订阅方的迁移仍要幂等，补偿与事件可能同时到。</p>
 *
 * <p>⚠️ <b>它在 trade 的事务提交、连接归还之后才发布，不在事务里发</b>（捐款批压测撞出来的）。
 * 早先是在事务里发、订阅方 {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW} 回写——
 * 看着没毛病，但 AFTER_COMMIT 回调跑在 {@code afterCompletion} 里，<b>那时 trade 事务的连接还没还给连接池</b>，
 * {@code REQUIRES_NEW} 又去要第二条：<b>每个付款回调同时占两条连接</b>。并发回调数一到连接池大小（Hikari 默认 10），
 * 每个线程都攥着一条、等着另一条，整池卡死 30 秒，期间所有接口拿不到连接，回写全部失败。
 * 所以订阅方写成 {@code @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)}：
 * 正常情况（没有外层事务）立即执行、自己开事务，一个线程只用一条连接；
 * 万一日后有人在外层事务里调 {@code applyPaidResult}，它仍会等到外层提交之后才跑，不会读到没提交的数据。
 * 由 donate 的 {@code PaymentListenerConnectionTest}（连接池只给 1 条）钉住。</p>
 *
 * @param tradeOrderId  交易单 id
 * @param bizType       业务类型（见 {@code TradeFlow.BIZ_*}）
 * @param bizNo         业务单据号
 * @param amountFen     实付金额（分）
 * @param transactionId 渠道支付单号
 * @param volunteerId   付款人
 * @author hengde
 */
public record TradePaidEvent(Long tradeOrderId, Integer bizType, String bizNo, Integer amountFen,
                             String transactionId, Long volunteerId) {
}
