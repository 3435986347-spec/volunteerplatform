package com.hengde.trade.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.dao.TradeMappers.TradeOrderMapper;
import com.hengde.trade.dao.TradeMappers.TradePaymentMapper;
import com.hengde.trade.dao.TradeMappers.TradeReconcileRunMapper;
import com.hengde.trade.entity.TradeOrder;
import com.hengde.trade.entity.TradeReconcileRun;
import com.hengde.trade.gateway.PaymentGateway;
import com.hengde.trade.vo.TradeVOs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 对账——<b>支付回写四道里的第四道，不是唯一那道</b>（V3规划 D2）。
 *
 * <p>逐单拿渠道的查单结果与本地比对，<b>把对不上的逐条列出来</b>。只报一个「全部正常」没有意义：
 * 对账的价值就在于把差异摆到人面前。所以<b>每一次对账都落库</b>（{@code trade_reconcile_run}），
 * 每日定时那一次也不例外——只写 ERROR 日志等于把「钱收了、单没发货」变成一条无人读的日志。</p>
 *
 * <p><b>两侧都要核</b>：</p>
 * <ul>
 *   <li>本地<b>已支付</b>的单——渠道那边真的收到了吗、金额对吗；</li>
 *   <li>本地<b>已关闭</b>的单——渠道那边是不是其实收到了钱。用户在关单边界上付款、
 *       后台关单之后渠道侧关单失败，钱都会付进一张本地已关闭的单；
 *       回调那一侧把这种情况记成「流水照记、状态不改、打 ERROR」，<b>而那行 ERROR 需要一个会被人看到的出口，就是这里</b>。</li>
 * </ul>
 *
 * <p><b>范围必须显式给出</b>（同证书的人工补发接口）：不给下界的话，第一次跑就会把全部历史单
 * 逐单去渠道查一遍——查单是有频率限制的，也会把一次请求拖到网关超时。</p>
 *
 * <p>⚠️ <b>渠道未开通时 {@code skipped = true}</b>，且 {@code matchedCount} 为 0：
 * 此时<b>什么都没有核对过</b>，别把这个结果读成「一致」。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class TradeReconcileService {

    /** 单次对账的封顶单数（已支付 + 已关闭）：再多请缩小时间窗（同证书补发接口的处理）。 */
    static final int MAX_ORDERS = 2000;

    public static final int TRIGGER_DAILY = 1;
    public static final int TRIGGER_MANUAL = 2;

    private TradeOrderMapper orderMapper;
    private TradePaymentMapper paymentMapper;
    private TradeReconcileRunMapper runMapper;
    private PaymentGateway gateway;

    @Autowired
    public void setOrderMapper(TradeOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Autowired
    public void setPaymentMapper(TradePaymentMapper paymentMapper) {
        this.paymentMapper = paymentMapper;
    }

    @Autowired
    public void setRunMapper(TradeReconcileRunMapper runMapper) {
        this.runMapper = runMapper;
    }

    @Autowired
    public void setGateway(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * 后台手动对账。操作人硬校验非空（trade 拿不到登录态，D1 的规矩 1）。
     *
     * @param from 起（含）
     * @param to   止（不含）——区间左闭右开，与项目里其它按时间切片的口径一致
     */
    public TradeVOs.Reconciliation reconcileManually(LocalDateTime from, LocalDateTime to, Long operatorId) {
        if (operatorId == null) {
            throw new BusinessException("操作人不能为空");
        }
        return reconcile(from, to, TRIGGER_MANUAL, operatorId);
    }

    /** 每日定时对账：核对某一天 [当天 0 点, 次日 0 点)。 */
    public TradeVOs.Reconciliation reconcileDay(LocalDate day) {
        if (day == null) {
            throw new BusinessException("对账日期不能为空");
        }
        return reconcile(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), TRIGGER_DAILY, null);
    }

    private TradeVOs.Reconciliation reconcile(LocalDateTime from, LocalDateTime to, int triggerType, Long operatorId) {
        if (from == null || to == null) {
            throw new BusinessException("对账必须显式给出时间范围（起、止都要）");
        }
        if (!to.isAfter(from)) {
            throw new BusinessException("结束时间须晚于开始时间");
        }
        TradeVOs.Reconciliation result = new TradeVOs.Reconciliation();
        result.setFrom(from);
        result.setTo(to);
        result.setTriggerType(triggerType);
        result.setTriggerLabel(triggerLabel(triggerType));
        result.setOperatorId(operatorId);
        List<TradeOrder> paid = orderMapper.selectPaidBetween(from, to,
                TradeFlow.ORDER_PAID, TradeFlow.ORDER_PARTIAL_REFUNDED, TradeFlow.ORDER_REFUNDED);
        List<TradeOrder> closed = orderMapper.selectClosedBetween(from, to, TradeFlow.ORDER_CLOSED);
        result.setLocalPaidCount(paid.size());
        result.setLocalClosedCount(closed.size());
        int total = paid.size() + closed.size();
        if (total > MAX_ORDERS) {
            throw new BusinessException("这段时间内有 " + total + " 单，超过单次上限 "
                    + MAX_ORDERS + "，请缩小时间范围分批对账");
        }
        if (!gateway.enabled()) {
            // 没开通就如实说「没对过」，不返回一个看起来干净的结果；这一次「没对过」本身也要落库
            result.setSkipped(true);
            return persist(result);
        }
        int matched = 0;
        for (TradeOrder order : paid) {
            TradeVOs.Mismatch m = checkPaid(order);
            if (m == null) {
                matched++;
            } else {
                result.getMismatches().add(m);
            }
        }
        for (TradeOrder order : closed) {
            TradeVOs.Mismatch m = checkClosed(order);
            if (m == null) {
                matched++;
            } else {
                result.getMismatches().add(m);
            }
        }
        result.setMatchedCount(matched);
        result.setMismatchCount(result.getMismatches().size());
        persist(result);
        if (!result.getMismatches().isEmpty()) {
            log.error("[TRADE] 对账发现 {} 处差异（{} ~ {}），对账记录 id={}",
                    result.getMismatchCount(), from, to, result.getId());
        }
        return result;
    }

    /** 本地已支付的单：渠道那边真的收到了吗、金额对吗。一致返回 null。 */
    private TradeVOs.Mismatch checkPaid(TradeOrder order) {
        PaymentGateway.QueryResult r = query(order);
        if (r == null) {
            return mismatch(order, false, null, "查单失败，未能核对");
        }
        if (!r.found()) {
            return mismatch(order, false, null, "本地记为已支付，渠道查无此单");
        }
        if (!r.paid()) {
            return mismatch(order, false, r.amountFen(), "本地记为已支付，渠道显示未支付");
        }
        if (!Objects.equals(r.amountFen(), order.getAmount())) {
            return mismatch(order, true, r.amountFen(), "金额不一致");
        }
        return null;
    }

    /**
     * 本地已关闭的单：渠道那边是不是其实收到了钱。一致返回 null。
     *
     * <p>先看本地有没有支付流水——回调在关单之后才到时，{@code applyPaidResult} 会「流水照记、状态不改」，
     * 那种单不必再花一次查单就能定性。</p>
     */
    private TradeVOs.Mismatch checkClosed(TradeOrder order) {
        if (paymentMapper.countByOrder(order.getId()) > 0) {
            return mismatch(order, true, null, "本地已关闭，但已收到支付流水——钱在账上、单没成交，需人工退款");
        }
        PaymentGateway.QueryResult r = query(order);
        if (r == null) {
            return mismatch(order, false, null, "查单失败，未能核对");
        }
        if (r.found() && r.paid()) {
            return mismatch(order, true, r.amountFen(),
                    "本地已关闭，渠道显示已支付——回调与扫描都没接住这笔钱，需人工退款");
        }
        return null;
    }

    /** 查单；失败返回 null（记为差异而不是中断整轮：一单查不到不该让其它单都不核）。 */
    private PaymentGateway.QueryResult query(TradeOrder order) {
        try {
            return gateway.query(order.getOutTradeNo());
        } catch (RuntimeException e) {
            log.error("[TRADE] 对账查单失败 outTradeNo={}", order.getOutTradeNo(), e);
            return null;
        }
    }

    private TradeVOs.Reconciliation persist(TradeVOs.Reconciliation r) {
        TradeReconcileRun run = new TradeReconcileRun();
        run.setWindowFrom(r.getFrom());
        run.setWindowTo(r.getTo());
        run.setTriggerType(r.getTriggerType());
        run.setOperatorId(r.getOperatorId());
        run.setSkipped(r.isSkipped() ? 1 : 0);
        run.setLocalPaidCount(r.getLocalPaidCount());
        run.setLocalClosedCount(r.getLocalClosedCount());
        run.setMatchedCount(r.getMatchedCount());
        run.setMismatchCount(r.getMismatches().size());
        run.setMismatchesJson(r.getMismatches().isEmpty() ? null : JSONUtil.toJsonStr(r.getMismatches()));
        runMapper.insert(run);
        r.setId(run.getId());
        r.setCreateTime(run.getCreateTime());
        return r;
    }

    // ================= 对账记录 =================

    /**
     * 对账记录列表，新的在前。{@code onlyMismatch=true} 只看有差异的——
     * 每天一行，真正要看的是其中那几行。
     */
    public PageResult<TradeVOs.Reconciliation> listRuns(PageQuery query, Boolean onlyMismatch, Boolean onlySkipped) {
        IPage<TradeReconcileRun> page = runMapper.selectPage(query.toPage(), Wrappers.<TradeReconcileRun>lambdaQuery()
                .select(TradeReconcileRun.class, f -> !"mismatches_json".equals(f.getColumn()))
                .gt(Boolean.TRUE.equals(onlyMismatch), TradeReconcileRun::getMismatchCount, 0)
                .eq(Boolean.TRUE.equals(onlySkipped), TradeReconcileRun::getSkipped, 1)
                .orderByDesc(TradeReconcileRun::getId));
        return PageResult.of(page.convert(run -> toVO(run, false)));
    }

    public TradeVOs.Reconciliation runDetail(Long id) {
        TradeReconcileRun run = id == null ? null : runMapper.selectById(id);
        if (run == null) {
            throw new BusinessException("对账记录不存在");
        }
        return toVO(run, true);
    }

    private static TradeVOs.Reconciliation toVO(TradeReconcileRun run, boolean withMismatches) {
        TradeVOs.Reconciliation vo = new TradeVOs.Reconciliation();
        vo.setId(run.getId());
        vo.setFrom(run.getWindowFrom());
        vo.setTo(run.getWindowTo());
        vo.setTriggerType(run.getTriggerType());
        vo.setTriggerLabel(triggerLabel(run.getTriggerType()));
        vo.setOperatorId(run.getOperatorId());
        vo.setSkipped(Objects.equals(run.getSkipped(), 1));
        vo.setLocalPaidCount(nz(run.getLocalPaidCount()));
        vo.setLocalClosedCount(nz(run.getLocalClosedCount()));
        vo.setMatchedCount(nz(run.getMatchedCount()));
        vo.setMismatchCount(nz(run.getMismatchCount()));
        vo.setCreateTime(run.getCreateTime());
        if (withMismatches && StringUtils.hasText(run.getMismatchesJson())) {
            vo.setMismatches(JSONUtil.toList(run.getMismatchesJson(), TradeVOs.Mismatch.class));
        }
        return vo;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    static String triggerLabel(Integer t) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case TRIGGER_DAILY -> "每日定时";
            case TRIGGER_MANUAL -> "后台手动";
            default -> "未知";
        };
    }

    private static TradeVOs.Mismatch mismatch(TradeOrder order, boolean remotePaid, Integer remoteAmount,
                                              String note) {
        TradeVOs.Mismatch m = new TradeVOs.Mismatch();
        m.setOutTradeNo(order.getOutTradeNo());
        m.setLocalStatus(order.getStatus());
        m.setLocalStatusLabel(TradeFlow.orderLabel(order.getStatus()));
        m.setLocalAmountFen(order.getAmount());
        m.setRemotePaid(remotePaid);
        m.setRemoteAmountFen(remoteAmount);
        m.setNote(note);
        return m;
    }
}
