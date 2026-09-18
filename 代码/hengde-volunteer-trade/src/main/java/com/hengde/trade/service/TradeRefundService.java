package com.hengde.trade.service;

import com.hengde.common.exception.BusinessException;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.dao.TradeMappers.TradeOrderMapper;
import com.hengde.trade.dao.TradeMappers.TradeRefundMapper;
import com.hengde.trade.dto.TradeDTOs;
import com.hengde.trade.entity.TradeOrder;
import com.hengde.trade.entity.TradeRefund;
import com.hengde.trade.event.TradeRefundedEvent;
import com.hengde.trade.gateway.PaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 退款（V3 trade 批）。<b>默认整单退</b>（《协会待确认清单-v3》⑭ 的 V3 口径）。
 *
 * <p><b>退款是异步的</b>：渠道受理只代表「收到了」。因此本地先落一条「处理中」的退款流水、
 * 并把已退金额记上（上限写在 UPDATE 的 WHERE 里），再去调渠道；
 * 真正的成败以回调 / 查询回写为准（{@link #applyRefundResult}，幂等）。</p>
 *
 * <p><b>顺序是承重的</b>：先占额度再调渠道。反过来的话，渠道已经受理而本地还没记，
 * 重试一次就会退两笔——而钱退出去是收不回来的。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class TradeRefundService {

    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TradeOrderMapper orderMapper;
    private TradeRefundMapper refundMapper;
    private PaymentGateway gateway;
    private ApplicationEventPublisher eventPublisher;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setOrderMapper(TradeOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Autowired
    public void setRefundMapper(TradeRefundMapper refundMapper) {
        this.refundMapper = refundMapper;
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

    /**
     * 发起退款。金额不填＝整单剩余全退。
     *
     * @param orderId    交易单 id
     * @param dto        金额与原因
     * @param operatorId 操作人（api 控制器从登录态取后传进来，D1 的规矩 1）
     * @return 我方退款单号
     */
    public String refund(Long orderId, TradeDTOs.Refund dto, Long operatorId) {
        if (operatorId == null) {
            throw new BusinessException("操作人不能为空");
        }
        if (dto == null || !StringUtils.hasText(dto.getReason())) {
            throw new BusinessException("请填写退款原因");
        }
        if (dto.getReason().trim().length() > 512) {
            throw new BusinessException("退款原因不超过 512 字");
        }
        // 事务内：占额度 + 落「处理中」的流水；渠道调用放在事务之外（网络几百毫秒，不该占着行锁）
        Prepared prepared = transactionTemplate.execute(s -> prepare(orderId, dto, operatorId));
        if (prepared == null) {
            throw new BusinessException("退款发起失败");
        }
        if (!gateway.enabled()) {
            // 渠道没开通就别把单子留在「处理中」骗人——直接置失败，额度退回去
            markFailed(prepared);
            throw new BusinessException("微信支付尚未开通，无法退款");
        }
        try {
            PaymentGateway.RefundResult r = gateway.refund(prepared.outTradeNo(), prepared.outRefundNo(),
                    prepared.amountFen(), prepared.totalFen(), dto.getReason().trim());
            if (r.success()) {
                // 渠道同步返回成功也只当「受理」：最终以回调 / 查询为准，那条路是幂等的
                log.info("[TRADE] 退款已受理 outRefundNo={} refundId={}", prepared.outRefundNo(), r.refundId());
            }
            return prepared.outRefundNo();
        } catch (RuntimeException e) {
            markFailed(prepared);
            log.error("[TRADE] 退款发起失败 orderId={} outRefundNo={}", orderId, prepared.outRefundNo(), e);
            throw new BusinessException("退款发起失败：" + e.getMessage());
        }
    }

    private Prepared prepare(Long orderId, TradeDTOs.Refund dto, Long operatorId) {
        TradeOrder order = orderMapper.selectByIdForUpdate(orderId);
        if (order == null) {
            throw new BusinessException("交易单不存在");
        }
        if (!Objects.equals(order.getStatus(), TradeFlow.ORDER_PAID)
                && !Objects.equals(order.getStatus(), TradeFlow.ORDER_PARTIAL_REFUNDED)) {
            throw new BusinessException("只有已支付的交易单可以退款（当前："
                    + TradeFlow.orderLabel(order.getStatus()) + "）");
        }
        int remaining = order.getAmount() - order.getRefundedAmount();
        int amount = dto.getAmountFen() == null ? remaining : dto.getAmountFen();
        if (amount <= 0) {
            throw new BusinessException("退款金额须大于 0");
        }
        if (amount > remaining) {
            throw new BusinessException("退款金额超过可退余额（还可退 " + TradeFlow.yuan(remaining) + " 元）");
        }
        // 上限条件在 SQL 里，影响行数为 0 说明有人同时退了一笔——不靠上面那次比较兜底
        int rows = orderMapper.addRefunded(order.getId(), amount, LocalDateTime.now(),
                TradeFlow.ORDER_PAID, TradeFlow.ORDER_PARTIAL_REFUNDED, TradeFlow.ORDER_REFUNDED);
        if (rows != 1) {
            throw new BusinessException("可退余额已变化，请刷新后重试");
        }
        TradeRefund r = new TradeRefund();
        r.setTradeOrderId(order.getId());
        r.setOutRefundNo(newOutRefundNo());
        r.setAmount(amount);
        r.setStatus(TradeFlow.REFUND_PROCESSING);
        r.setReason(dto.getReason().trim());
        r.setOperatorId(operatorId);
        refundMapper.insert(r);
        return new Prepared(r.getId(), order.getId(), order.getOutTradeNo(), r.getOutRefundNo(),
                amount, order.getAmount(), order.getBizType(), order.getBizNo());
    }

    /**
     * 退款结果回写（回调与主动查询共用，<b>幂等</b>）。
     *
     * <p>成功：流水置成功并发事件；失败：流水置失败<b>并把占掉的额度退回去</b>——
     * 不退回的话，那笔钱在账上永远算「已退」，之后再也退不出来。</p>
     */
    public void applyRefundResult(String outRefundNo, String refundId, boolean success,
                                  LocalDateTime successTime, String rawJson) {
        if (!StringUtils.hasText(outRefundNo)) {
            throw new BusinessException("退款结果缺少单号");
        }
        // 事件在事务之外发，理由同 TradeOrderService.applyPaidResult（见 TradePaidEvent 的连接池说明）
        TradeRefundedEvent[] refunded = new TradeRefundedEvent[1];
        transactionTemplate.execute(s -> {
            TradeRefund refund = refundMapper.selectByOutRefundNoForUpdate(outRefundNo);
            if (refund == null) {
                log.error("[TRADE] 收到未知退款单的结果 outRefundNo={} refundId={}", outRefundNo, refundId);
                throw new BusinessException("退款单不存在");
            }
            int rows = refundMapper.applyResult(refund.getId(), refundId, successTime, LocalDateTime.now(),
                    TradeFlow.REFUND_PROCESSING,
                    success ? TradeFlow.REFUND_SUCCESS : TradeFlow.REFUND_FAILED);
            if (rows != 1) {
                // 已经处理过了（重复投递），什么都不做
                return null;
            }
            if (rawJson != null) {
                TradeRefund patch = new TradeRefund();
                patch.setId(refund.getId());
                patch.setRawJson(rawJson);
                refundMapper.updateById(patch);
            }
            TradeOrder order = orderMapper.selectByIdForUpdate(refund.getTradeOrderId());
            if (!success) {
                releaseQuota(order, refund.getAmount());
                return null;
            }
            boolean fully = order != null
                    && Objects.equals(order.getRefundedAmount(), order.getAmount());
            refunded[0] = new TradeRefundedEvent(refund.getTradeOrderId(),
                    order == null ? null : order.getBizType(), order == null ? null : order.getBizNo(),
                    refund.getAmount(), outRefundNo, fully);
            return null;
        });
        if (refunded[0] != null) {
            eventPublisher.publishEvent(refunded[0]);
        }
    }

    /** 退款失败：把占掉的额度退回去，并把单子的状态改回「已支付」（若已无其它已退金额）。 */
    private void releaseQuota(TradeOrder order, int amount) {
        if (order == null) {
            return;
        }
        int back = order.getRefundedAmount() - amount;
        TradeOrder patch = new TradeOrder();
        patch.setId(order.getId());
        patch.setRefundedAmount(Math.max(0, back));
        patch.setStatus(back <= 0 ? TradeFlow.ORDER_PAID : TradeFlow.ORDER_PARTIAL_REFUNDED);
        patch.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(patch);
    }

    private void markFailed(Prepared prepared) {
        transactionTemplate.execute(s -> {
            TradeRefund refund = refundMapper.selectByOutRefundNoForUpdate(prepared.outRefundNo());
            if (refund == null) {
                return null;
            }
            refundMapper.applyResult(refund.getId(), null, null, LocalDateTime.now(),
                    TradeFlow.REFUND_PROCESSING, TradeFlow.REFUND_FAILED);
            releaseQuota(orderMapper.selectByIdForUpdate(prepared.tradeOrderId()), prepared.amountFen());
            return null;
        });
    }

    private static String newOutRefundNo() {
        StringBuilder sb = new StringBuilder("HR").append(LocalDateTime.now().format(NO_TIME));
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** 事务内算好、事务外用的那点东西。 */
    private record Prepared(Long refundId, Long tradeOrderId, String outTradeNo, String outRefundNo,
                            int amountFen, int totalFen, Integer bizType, String bizNo) {
    }
}
