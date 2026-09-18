package com.hengde.trade.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.trade.config.TradeProperties;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.dao.TradeMappers.TradeOrderMapper;
import com.hengde.trade.dao.TradeMappers.TradePaymentMapper;
import com.hengde.trade.dao.TradeMappers.TradeRefundMapper;
import com.hengde.trade.dto.TradeDTOs;
import com.hengde.trade.entity.TradeOrder;
import com.hengde.trade.entity.TradePayment;
import com.hengde.trade.event.TradePaidEvent;
import com.hengde.trade.gateway.PaymentGateway;
import com.hengde.trade.vo.TradeVOs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 交易单的全过程（V3 trade 批）：下单 → 支付 → 关单，以及<b>支付回写的唯一入口</b>。
 *
 * <p><b>D2 的核心在这里</b>：回调、主动查单、分钟级扫描<b>三条路走同一个方法</b>
 * {@link #applyPaidResult}，且该方法幂等、可被重复驱动。
 * 三条路各自都会丢（事件不持久、网络会断、任务会停），但只要它们最终都汇到这一个方法，
 * 「钱收了、单没发货」就只是延迟而不是丢失。</p>
 *
 * <p><b>网络调用一律在事务之外</b>：向渠道下单 / 查单要几百毫秒到几秒，
 * 把它包在事务里会让数据库连接与行锁陪着等——先算好、再进事务写。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class TradeOrderService {

    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NO_RETRY = 5;

    private TradeOrderMapper orderMapper;
    private TradePaymentMapper paymentMapper;
    private TradeRefundMapper refundMapper;
    private TradeProperties properties;
    private PaymentGateway gateway;
    private ApplicationEventPublisher eventPublisher;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setOrderMapper(TradeOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Autowired
    public void setPaymentMapper(TradePaymentMapper paymentMapper) {
        this.paymentMapper = paymentMapper;
    }

    @Autowired
    public void setRefundMapper(TradeRefundMapper refundMapper) {
        this.refundMapper = refundMapper;
    }

    @Autowired
    public void setProperties(TradeProperties properties) {
        this.properties = properties;
    }

    @Autowired
    public void setGateway(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Autowired
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Autowired
    public void setTransactionTemplate(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    // ================= 下单 =================

    /**
     * 下单。<b>同一条业务记录同时只会有一张「活」交易单</b>：已有待支付的就复用它（重新取一次支付参数），
     * 已支付的直接拒绝——重复下单会让同一笔业务收两次钱。
     *
     * <p>渠道未开通时<b>明确拒绝</b>，不返回一个假的成功：没有商户资质就是不能收钱，
     * 伪造一个「已下单」只会把问题推到用户点了支付之后。</p>
     */
    public TradeVOs.Prepay createOrder(TradeDTOs.CreateOrder dto) {
        validate(dto);
        if (dto.getExpireAt() != null && !dto.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException("已超过付款时限");
        }
        // 先问渠道开没开通，再落库：反过来的话，每一次「支付未开通」都会留下一张待支付的空单，
        // 等着扫描任务去关——凭空多出一批没人付过的单，对账时还得先把它们解释掉
        if (!gateway.enabled()) {
            throw new BusinessException("微信支付尚未开通，暂时无法付款");
        }
        TradeOrder existing = findActive(dto.getBizType(), dto.getBizNo());
        if (existing != null && Objects.equals(existing.getStatus(), TradeFlow.ORDER_PAID)) {
            throw new BusinessException("这笔业务已经支付过了，不能重复下单");
        }
        TradeOrder order = existing != null ? existing : insertOrder(dto);
        return prepay(order, dto.getPayerOpenid());
    }

    private TradeOrder insertOrder(TradeDTOs.CreateOrder dto) {
        LocalDateTime now = LocalDateTime.now();
        int ttl = properties.getTtlMinutesByBizType()
                .getOrDefault(dto.getBizType(), properties.getDefaultTtlMinutes());
        LocalDateTime expire = now.plusMinutes(ttl);
        if (dto.getExpireAt() != null && dto.getExpireAt().isBefore(expire)) {
            // 业务单据的付款时限更早：交易单不能比它活得久，否则会在业务单超时之后付进来
            expire = dto.getExpireAt().withNano(0);
        }
        for (int attempt = 0; attempt < NO_RETRY; attempt++) {
            TradeOrder o = new TradeOrder();
            o.setOutTradeNo(newOutTradeNo());
            o.setBizType(dto.getBizType());
            o.setBizNo(dto.getBizNo().trim());
            o.setVolunteerId(dto.getVolunteerId());
            o.setSubject(dto.getSubject().trim());
            o.setAmount(dto.getAmountFen());
            o.setRefundedAmount(0);
            o.setStatus(TradeFlow.ORDER_PENDING);
            o.setChannel(TradeFlow.CHANNEL_WECHAT);
            o.setExpireTime(expire);
            o.setRemark(dto.getRemark());
            try {
                orderMapper.insert(o);
                return o;
            } catch (DuplicateKeyException e) {
                // 撞 uk_active_biz：同一业务已经有活单了（并发下单），读回来复用
                TradeOrder live = findActive(dto.getBizType(), dto.getBizNo());
                if (live != null) {
                    if (Objects.equals(live.getStatus(), TradeFlow.ORDER_PAID)) {
                        throw new BusinessException("这笔业务已经支付过了，不能重复下单");
                    }
                    return live;
                }
                // 否则是单号碰撞：换一个重试（同证书编号那条）
                log.warn("交易单号碰撞，换号重试 attempt={} bizType={} bizNo={}",
                        attempt + 1, dto.getBizType(), dto.getBizNo());
            }
        }
        throw new BusinessException("交易单号生成失败，请重试");
    }

    /** 取支付参数。渠道调用在事务之外（本方法本就不在事务里）。 */
    private TradeVOs.Prepay prepay(TradeOrder order, String payerOpenid) {
        TradeVOs.Prepay vo = new TradeVOs.Prepay();
        vo.setTradeOrderId(order.getId());
        vo.setOutTradeNo(order.getOutTradeNo());
        vo.setAmountFen(order.getAmount());
        vo.setAmountYuan(TradeFlow.yuan(order.getAmount()));
        vo.setExpireTime(order.getExpireTime());
        if (!gateway.enabled()) {
            throw new BusinessException("微信支付尚未开通，暂时无法付款");
        }
        if (!StringUtils.hasText(payerOpenid)) {
            throw new BusinessException("缺少付款人 openid，无法唤起支付");
        }
        PaymentGateway.PrepayResult r = gateway.prepay(order.getOutTradeNo(), order.getAmount(),
                order.getSubject(), payerOpenid, order.getExpireTime());
        vo.setPrepayId(r.prepayId());
        vo.setPaySign(r.paySign());
        vo.setNonceStr(r.nonceStr());
        vo.setTimeStamp(r.timeStamp());
        vo.setSignType(r.signType());
        return vo;
    }

    // ================= 支付回写：三条路的唯一入口 =================

    /**
     * 把一次「已支付」的事实写回本地。<b>回调、主动查单、扫描任务都调它</b>，且<b>幂等</b>。
     *
     * <p>三件事按顺序做，每一件都必须挡得住重复：</p>
     * <ol>
     *   <li><b>金额比对</b>——渠道金额与本地单不符一律拒绝并记 ERROR。
     *       这不是防错，是防<b>伪造回调</b>：验签之外的第二道。</li>
     *   <li><b>支付流水靠 {@code uk_transaction} 去重</b>，撞键说明这条通知已经处理过，直接返回；
     *       撞键后取回赢家用当前读（{@code FOR SHARE}），理由同积分账本那条。</li>
     *   <li><b>交易单 CAS 待支付 → 已支付</b>，只有一条会成功；成功的那条发领域事件（业务回写）。</li>
     * </ol>
     *
     * <p>⚠️ <b>已关闭的单收到支付</b>（用户在关单之后才付）：钱是真收了，
     * 所以流水照记、状态不改，并记一条 ERROR 等人工退款——<b>静默丢弃等于把一笔已收的钱从系统里抹掉</b>。</p>
     *
     * @return 本次是否真的完成了「待支付 → 已支付」的迁移（重复投递返回 false）
     */
    public boolean applyPaidResult(String outTradeNo, String transactionId, int amountFen,
                                   LocalDateTime successTime, String payerOpenid, String rawJson, int source) {
        if (!StringUtils.hasText(outTradeNo) || !StringUtils.hasText(transactionId)) {
            throw new BusinessException("支付结果缺少单号");
        }
        // 事件在事务<b>之外</b>发（见 TradePaidEvent 的连接池说明）；事务里只把它备好
        TradePaidEvent[] paid = new TradePaidEvent[1];
        Boolean moved = transactionTemplate.execute(s -> {
            TradeOrder order = orderMapper.selectByOutTradeNoForUpdate(outTradeNo);
            if (order == null) {
                // 不是我们的单：可能是别的系统共用了同一个商户号，也可能是伪造的通知
                log.error("[TRADE] 收到未知交易单的支付结果 outTradeNo={} transactionId={}", outTradeNo, transactionId);
                throw new BusinessException("交易单不存在");
            }
            if (!Objects.equals(order.getAmount(), amountFen)) {
                log.error("[TRADE] 支付金额与本地单不符，已拒绝 outTradeNo={} 本地={}分 渠道={}分 transactionId={}",
                        outTradeNo, order.getAmount(), amountFen, transactionId);
                throw new BusinessException("支付金额与交易单不符");
            }
            if (!insertPayment(order, transactionId, amountFen, successTime, payerOpenid, rawJson, source)) {
                return false;
            }
            LocalDateTime now = LocalDateTime.now();
            int rows = orderMapper.markPaid(order.getId(), transactionId,
                    successTime == null ? now : successTime, now,
                    TradeFlow.ORDER_PENDING, TradeFlow.ORDER_PAID);
            if (rows != 1) {
                if (Objects.equals(order.getStatus(), TradeFlow.ORDER_CLOSED)) {
                    log.error("[TRADE] 已关闭的交易单收到支付，需人工退款 outTradeNo={} transactionId={} 金额={}分",
                            outTradeNo, transactionId, amountFen);
                }
                return false;
            }
            paid[0] = new TradePaidEvent(order.getId(), order.getBizType(), order.getBizNo(),
                    amountFen, transactionId, order.getVolunteerId());
            return true;
        });
        if (paid[0] != null) {
            eventPublisher.publishEvent(paid[0]);
        }
        return Boolean.TRUE.equals(moved);
    }

    /**
     * 支付流水落库。
     *
     * @return false = 这条通知之前已经处理过（撞了 {@code uk_transaction}）
     */
    private boolean insertPayment(TradeOrder order, String transactionId, int amountFen,
                                  LocalDateTime successTime, String payerOpenid, String rawJson, int source) {
        TradePayment p = new TradePayment();
        p.setTradeOrderId(order.getId());
        p.setTransactionId(transactionId);
        p.setAmount(amountFen);
        p.setPayerOpenid(payerOpenid);
        p.setSuccessTime(successTime);
        p.setRawJson(rawJson);
        p.setSource(source);
        try {
            paymentMapper.insert(p);
            return true;
        } catch (DuplicateKeyException e) {
            TradePayment winner = paymentMapper.selectByTransactionForShare(transactionId);
            if (winner != null && !Objects.equals(winner.getTradeOrderId(), order.getId())) {
                // 同一个渠道单号落在了两张交易单上：这只可能是数据错乱或伪造，必须喊出来
                log.error("[TRADE] 渠道支付单号与交易单对不上 transactionId={} 已挂单={} 本次单={}",
                        transactionId, winner.getTradeOrderId(), order.getId());
                throw new BusinessException("支付流水与交易单不匹配");
            }
            return false;
        }
    }

    /**
     * 主动查单并同步本地（D2 的第一道与第三道共用）。<b>渠道调用在事务之外</b>。
     *
     * <p>「本地与渠道不一致时以查单结果为准」——这就是那句话的落点。
     * 查不到且已过期的单顺手关掉，免得它永远躺在扫描集合里。</p>
     */
    public boolean queryAndSync(String outTradeNo, int source) {
        if (!gateway.enabled()) {
            return false;
        }
        PaymentGateway.QueryResult r = gateway.query(outTradeNo);
        if (r.found() && r.paid()) {
            return applyPaidResult(outTradeNo, r.transactionId(),
                    r.amountFen() == null ? -1 : r.amountFen(), r.successTime(), null, r.rawJson(), source);
        }
        closeIfExpired(outTradeNo);
        return false;
    }

    /** 过期未支付的单关掉（CAS，只有待支付会被改）。 */
    public void closeIfExpired(String outTradeNo) {
        transactionTemplate.execute(s -> {
            TradeOrder order = orderMapper.selectByOutTradeNoForUpdate(outTradeNo);
            if (order == null || !Objects.equals(order.getStatus(), TradeFlow.ORDER_PENDING)) {
                return null;
            }
            if (order.getExpireTime() != null && order.getExpireTime().isAfter(LocalDateTime.now())) {
                return null;
            }
            orderMapper.close(order.getId(), LocalDateTime.now(), TradeFlow.ORDER_PENDING, TradeFlow.ORDER_CLOSED);
            return null;
        });
    }

    /** 后台关单。已支付的不能关——那等于把收到的钱从账上抹掉。 */
    public void close(Long orderId, Long operatorId) {
        requireOperator(operatorId);
        transactionTemplate.execute(s -> {
            TradeOrder order = orderMapper.selectByIdForUpdate(orderId);
            if (order == null) {
                throw new BusinessException("交易单不存在");
            }
            int rows = orderMapper.close(order.getId(), LocalDateTime.now(),
                    TradeFlow.ORDER_PENDING, TradeFlow.ORDER_CLOSED);
            if (rows != 1) {
                throw new BusinessException("只有待支付的交易单可以关闭（当前："
                        + TradeFlow.orderLabel(order.getStatus()) + "）");
            }
            return null;
        });
        if (gateway.enabled()) {
            // 渠道侧也要关，否则用户仍能付进来。失败只记日志：本地已关，扫描任务会再发现它
            try {
                TradeOrder order = orderMapper.selectById(orderId);
                gateway.close(order.getOutTradeNo());
            } catch (RuntimeException e) {
                log.error("[TRADE] 渠道关单失败 orderId={}", orderId, e);
            }
        }
    }

    // ================= 给业务域用的三个入口（商城快递批起） =================

    /** 渠道是否开通。业务侧在「要不要让用户走到付款这一步」之前先问它，免得占住库存的单根本付不了。 */
    public boolean paymentEnabled() {
        return gateway.enabled();
    }

    /** 某条业务记录<b>最近的</b>一张交易单（含已关闭的），没有返回 null。业务侧的补偿任务靠它问「付了没有」。 */
    public TradeOrder findLatestByBiz(int bizType, String bizNo) {
        if (!StringUtils.hasText(bizNo)) {
            return null;
        }
        return orderMapper.selectOne(Wrappers.<TradeOrder>lambdaQuery()
                .eq(TradeOrder::getBizType, bizType)
                .eq(TradeOrder::getBizNo, bizNo.trim())
                .orderByDesc(TradeOrder::getId)
                .last("LIMIT 1"));
    }

    /**
     * 业务侧关掉某条业务记录的「活」交易单（用户取消待支付的兑换单、业务单超时）。<b>没有操作人</b>——
     * 它不是后台的关单动作，而是业务单据自己的生命周期。
     *
     * <p><b>返回 false 表示「关不掉，因为已经付了」</b>：调用方必须据此放弃取消，否则就是钱收了、单子没了。
     * 没有活单、或本来就是待支付且 CAS 关掉了，返回 true。渠道侧关单失败只记日志（本地已关，扫描任务会再发现它），
     * 与后台关单同一条处理。</p>
     *
     * <p>CAS 失败后的复核是<b>当前读</b>（{@code selectByIdForUpdate}）：同一时刻回调正在把它推成已支付，
     * 普通读可能还看见「待支付」，于是把「已付」误报成「关掉了」。</p>
     */
    public boolean closeByBiz(int bizType, String bizNo) {
        TradeOrder active = findActive(bizType, bizNo);
        if (active == null) {
            return true;
        }
        Boolean closed = transactionTemplate.execute(s -> {
            int rows = orderMapper.close(active.getId(), LocalDateTime.now(),
                    TradeFlow.ORDER_PENDING, TradeFlow.ORDER_CLOSED);
            if (rows == 1) {
                return true;
            }
            TradeOrder now = orderMapper.selectByIdForUpdate(active.getId());
            return now == null || !TradeFlow.isPaidLike(now.getStatus());
        });
        if (Boolean.TRUE.equals(closed) && gateway.enabled()) {
            try {
                gateway.close(active.getOutTradeNo());
            } catch (RuntimeException e) {
                log.error("[TRADE] 渠道关单失败 outTradeNo={}", active.getOutTradeNo(), e);
            }
        }
        return Boolean.TRUE.equals(closed);
    }

    // ================= 查询 =================

    public PageResult<TradeVOs.Order> list(PageQuery query, Integer bizType, Integer status,
                                            LocalDateTime from, LocalDateTime to) {
        IPage<TradeOrder> page = orderMapper.selectPage(query.toPage(), Wrappers.<TradeOrder>lambdaQuery()
                .eq(bizType != null, TradeOrder::getBizType, bizType)
                .eq(status != null, TradeOrder::getStatus, status)
                .ge(from != null, TradeOrder::getCreateTime, from)
                .lt(to != null, TradeOrder::getCreateTime, to)
                .orderByDesc(TradeOrder::getId));
        return PageResult.of(page.convert(TradeOrderService::toVO));
    }

    public TradeVOs.Order detail(Long id) {
        TradeOrder order = id == null ? null : orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException("交易单不存在");
        }
        TradeVOs.Order vo = toVO(order);
        paymentMapper.selectByOrder(id).forEach(p -> {
            TradeVOs.Payment pv = new TradeVOs.Payment();
            pv.setId(p.getId());
            pv.setTransactionId(p.getTransactionId());
            pv.setAmountFen(p.getAmount());
            pv.setAmountYuan(TradeFlow.yuan(p.getAmount()));
            pv.setPayerOpenid(p.getPayerOpenid());
            pv.setSuccessTime(p.getSuccessTime());
            pv.setSource(p.getSource());
            pv.setCreateTime(p.getCreateTime());
            vo.getPayments().add(pv);
        });
        refundMapper.selectByOrder(id).forEach(r -> {
            TradeVOs.Refund rv = new TradeVOs.Refund();
            rv.setId(r.getId());
            rv.setOutRefundNo(r.getOutRefundNo());
            rv.setRefundId(r.getRefundId());
            rv.setAmountFen(r.getAmount());
            rv.setAmountYuan(TradeFlow.yuan(r.getAmount()));
            rv.setStatus(r.getStatus());
            rv.setStatusLabel(TradeFlow.refundLabel(r.getStatus()));
            rv.setReason(r.getReason());
            rv.setSuccessTime(r.getSuccessTime());
            rv.setCreateTime(r.getCreateTime());
            vo.getRefunds().add(rv);
        });
        return vo;
    }

    /** 扫描任务用：捞「待支付、已过冷却期」的单。 */
    public List<TradeOrder> due(int limit) {
        LocalDateTime before = LocalDateTime.now().minusMinutes(properties.getScan().getMinAgeMinutes());
        return orderMapper.selectDue(TradeFlow.ORDER_PENDING, before, limit);
    }

    /** 按 id 取交易单（api 控制器拿它换 out_trade_no 再去查单）。 */
    public TradeOrder requireById(Long id) {
        TradeOrder order = id == null ? null : orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException("交易单不存在");
        }
        return order;
    }

    public TradeOrder requireByOutTradeNo(String outTradeNo) {
        TradeOrder order = orderMapper.selectOne(Wrappers.<TradeOrder>lambdaQuery()
                .eq(TradeOrder::getOutTradeNo, outTradeNo));
        if (order == null) {
            throw new BusinessException("交易单不存在");
        }
        return order;
    }

    // ================= 内部 =================

    private TradeOrder findActive(Integer bizType, String bizNo) {
        return orderMapper.selectOne(Wrappers.<TradeOrder>lambdaQuery()
                .eq(TradeOrder::getBizType, bizType)
                .eq(TradeOrder::getBizNo, bizNo == null ? null : bizNo.trim())
                .in(TradeOrder::getStatus, TradeFlow.ORDER_PENDING, TradeFlow.ORDER_PAID)
                .last("LIMIT 1"));
    }

    private static void validate(TradeDTOs.CreateOrder dto) {
        if (dto == null) {
            throw new BusinessException("下单参数不能为空");
        }
        if (!TradeFlow.isValidBizType(dto.getBizType())) {
            throw new BusinessException("业务类型不正确");
        }
        if (!StringUtils.hasText(dto.getBizNo())) {
            throw new BusinessException("业务单据号不能为空");
        }
        if (!StringUtils.hasText(dto.getSubject())) {
            throw new BusinessException("商品描述不能为空");
        }
        if (dto.getAmountFen() == null || dto.getAmountFen() <= 0) {
            throw new BusinessException("金额须大于 0");
        }
    }

    /** 我方单号：时间 + 随机，够短也够不容易撞；撞了由调用处换号重试。 */
    private static String newOutTradeNo() {
        StringBuilder sb = new StringBuilder("HD").append(LocalDateTime.now().format(NO_TIME));
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static void requireOperator(Long operatorId) {
        // trade 不放 controller、拿不到登录态，操作人由调用方传入，这里硬校验非空（D1 的规矩 1）
        if (operatorId == null) {
            throw new BusinessException("操作人不能为空");
        }
    }

    static TradeVOs.Order toVO(TradeOrder o) {
        TradeVOs.Order vo = new TradeVOs.Order();
        vo.setId(o.getId());
        vo.setOutTradeNo(o.getOutTradeNo());
        vo.setBizType(o.getBizType());
        vo.setBizTypeLabel(TradeFlow.bizLabel(o.getBizType()));
        vo.setBizNo(o.getBizNo());
        vo.setVolunteerId(o.getVolunteerId());
        vo.setSubject(o.getSubject());
        vo.setAmountFen(o.getAmount());
        vo.setAmountYuan(TradeFlow.yuan(o.getAmount()));
        vo.setRefundedFen(o.getRefundedAmount());
        vo.setRefundedYuan(TradeFlow.yuan(o.getRefundedAmount()));
        vo.setStatus(o.getStatus());
        vo.setStatusLabel(TradeFlow.orderLabel(o.getStatus()));
        vo.setChannel(o.getChannel());
        vo.setExpireTime(o.getExpireTime());
        vo.setPayTime(o.getPayTime());
        vo.setCloseTime(o.getCloseTime());
        vo.setTransactionId(o.getTransactionId());
        vo.setRemark(o.getRemark());
        vo.setCreateTime(o.getCreateTime());
        return vo;
    }
}
