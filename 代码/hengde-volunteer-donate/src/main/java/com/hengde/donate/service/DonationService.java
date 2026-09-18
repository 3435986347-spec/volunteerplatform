package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.MiniappIdentityService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.config.DonationProperties;
import com.hengde.donate.constant.DonationFlow;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.dao.DonateDonationMapper;
import com.hengde.donate.dao.DonatePairMappers.DonateCrowdfundMapper;
import com.hengde.donate.dao.DonatePairMappers.DonatePairProjectMapper;
import com.hengde.donate.dao.DonatePairMappers.DonatePairRecordMapper;
import com.hengde.donate.dto.DonationDTOs;
import com.hengde.donate.entity.DonateCrowdfund;
import com.hengde.donate.entity.DonateDonation;
import com.hengde.donate.entity.DonatePairProject;
import com.hengde.donate.entity.DonatePairRecord;
import com.hengde.donate.vo.DonationVOs;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.dto.TradeDTOs;
import com.hengde.trade.entity.TradeOrder;
import com.hengde.trade.service.TradeOrderService;
import com.hengde.trade.service.TradeRefundService;
import com.hengde.trade.vo.TradeVOs;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 捐款（V3 捐款批）：众筹捐款（Row 16）与结对捐款（Row 10）付钱的那一半。
 *
 * <p>与商城快递批的现金付款同一套纪律，差别只在「钱到了之后记在哪」：</p>
 * <ul>
 *   <li><b>只有「已到账」计入已筹金额</b>——众筹记 {@code donate_crowdfund.raised_amount}，结对记
 *       {@code donate_pair_record.paid_amount} 与 {@code donate_pair_project.raised_amount}；退款减回；</li>
 *   <li><b>取消与付款不能两边都赢</b>：取消（本人 / 超时 / 结对被撤回或取消）先让 trade {@code closeByBiz}，
 *       关不掉（已付）就放弃；发起、付款、取消<b>持同一把「付款主体」锁</b>——结对捐款的主体是那一条结对
 *       （它至多一笔待支付），众筹捐款的主体是那一笔捐款本身；</li>
 *   <li><b>付款成功的推进不只靠事件</b>：{@link #onPaid} 由事件监听与 {@link #syncAwaitingDonations} 共用，幂等；</li>
 *   <li><b>退款先 CAS、提交后再原路退</b>，失败不回滚、原因落在记录上。</li>
 * </ul>
 *
 * <p><b>加锁顺序</b>（数据库行锁）：捐款 → 结对登记 → 项目。确认结对成立（登记 → 项目）与取消结对（登记 → 项目）
 * 走的也是「登记在前、项目在后」，反过来会与付款回写互等成死锁。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class DonationService {

    static final String LOCK_PREFIX = "lock:donation:subject:";
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private DonateDonationMapper donationMapper;
    private DonateCrowdfundMapper crowdfundMapper;
    private DonatePairProjectMapper projectMapper;
    private DonatePairRecordMapper recordMapper;
    private PairProjectService pairProjectService;
    private TradeOrderService tradeOrderService;
    private TradeRefundService tradeRefundService;
    private MiniappIdentityService identityService;
    private VolunteerQueryService volunteerQueryService;
    private DonationProperties properties;
    private RedissonClient redissonClient;
    private TransactionTemplate transactionTemplate;
    private TransactionTemplate requiresNew;

    @Autowired
    public void setDonationMapper(DonateDonationMapper donationMapper) {
        this.donationMapper = donationMapper;
    }

    @Autowired
    public void setCrowdfundMapper(DonateCrowdfundMapper crowdfundMapper) {
        this.crowdfundMapper = crowdfundMapper;
    }

    @Autowired
    public void setProjectMapper(DonatePairProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Autowired
    public void setRecordMapper(DonatePairRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    @Autowired
    public void setPairProjectService(PairProjectService pairProjectService) {
        this.pairProjectService = pairProjectService;
    }

    @Autowired
    public void setTradeOrderService(TradeOrderService tradeOrderService) {
        this.tradeOrderService = tradeOrderService;
    }

    @Autowired
    public void setTradeRefundService(TradeRefundService tradeRefundService) {
        this.tradeRefundService = tradeRefundService;
    }

    @Autowired
    public void setIdentityService(MiniappIdentityService identityService) {
        this.identityService = identityService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setProperties(DonationProperties properties) {
        this.properties = properties;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ================= 发起捐款 =================

    /**
     * 众筹捐款：落一笔待支付 → 让 trade 下单 → 返回唤起支付的参数。
     *
     * <p><b>先问支付开没开通、先换 openid，再落库</b>：反过来的话每一次「未开通」「code 失效」都会留下一笔没人付的待支付。</p>
     */
    public DonationVOs.Created donateToCrowdfund(Long crowdfundId, Long volunteerId, DonationDTOs.CrowdfundDonate dto) {
        requireVolunteer(volunteerId);
        if (dto == null) {
            throw new BusinessException("请填写捐款信息");
        }
        BigDecimal amount = checkAmount(dto.getAmount());
        Invoice invoice = checkInvoice(dto);
        requirePaymentEnabled();
        // 直接读 mapper 而不经 CrowdfundService：后者要反过来读捐款人数，经它就成环
        DonateCrowdfund c = crowdfundId == null ? null : crowdfundMapper.selectById(crowdfundId);
        if (c == null) {
            throw new BusinessException("项目不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!Objects.equals(c.getStatus(), PairFlow.CROWDFUND_OPEN)
                || (c.getStartTime() != null && now.isBefore(c.getStartTime()))
                || (c.getEndTime() != null && !now.isBefore(c.getEndTime()))) {
            throw new BusinessException("这个项目当前不在募集中");
        }
        if (Objects.equals(c.getAcceptMoney(), 0)) {
            throw new BusinessException("这个项目只接受捐物，不接受捐款");
        }
        String openid = identityService.openidOf(dto.getCode());
        DonateDonation d = newDonation(DonationFlow.BIZ_CROWDFUND, crowdfundId, null, volunteerId, c.getTitle(),
                amount, dto.getRemark(), invoice, now);
        donationMapper.insert(d);
        return DistributedLockSupport.runLocked(redissonClient, lockKeyOf(d),
                () -> created(d, startTrade(d, openid)));
    }

    /**
     * 结对捐款：付的是这条结对的「认捐额 − 已付」。须有一条自己的、未取消的结对登记。
     *
     * <p>持「这一条结对」的锁：发起、付款、取消、协会取消结对都在这把锁下，
     * 所以「刚关掉旧交易单、付款又建了一张新的」不会发生；「一条结对至多一笔待支付」另由生成列唯一键兜底。</p>
     */
    public DonationVOs.Created donateToPair(Long projectId, Long volunteerId, DonationDTOs.PairDonate dto) {
        requireVolunteer(volunteerId);
        if (dto == null) {
            throw new BusinessException("请填写捐款信息");
        }
        Invoice invoice = checkInvoice(dto);
        requirePaymentEnabled();
        DonatePairRecord live = recordMapper.selectActive(projectId, volunteerId);
        if (live == null) {
            throw new BusinessException("请先登记结对，再为这条结对付款");
        }
        String openid = identityService.openidOf(dto.getCode());
        return DistributedLockSupport.runLocked(redissonClient, pairLockKey(live.getId()), () -> {
            DonateDonation d = transactionTemplate.execute(s -> {
                DonatePairRecord r = recordMapper.selectByIdForUpdate(live.getId());
                if (r == null || Objects.equals(r.getStatus(), PairFlow.PAIR_CANCELLED)) {
                    throw new BusinessException("这条结对已经取消");
                }
                BigDecimal remaining = r.getAmount().subtract(nz(r.getPaidAmount()));
                if (remaining.signum() <= 0) {
                    throw new BusinessException("这条结对的认捐额已经付清");
                }
                checkAmount(remaining);
                DonatePairProject p = pairProjectService.require(projectId);
                DonateDonation row = newDonation(DonationFlow.BIZ_PAIR, projectId, r.getId(), volunteerId,
                        p.getTitle(), remaining, dto.getRemark(), invoice, LocalDateTime.now());
                try {
                    donationMapper.insert(row);
                } catch (DuplicateKeyException e) {
                    throw new BusinessException("这条结对已有一笔待付款的捐款，请先完成或取消它");
                }
                return row;
            });
            return created(d, startTrade(d, openid));
        });
    }

    /** 重新发起付款（关掉了付款弹窗、网络断了）。复用活的交易单。 */
    public TradeVOs.Prepay pay(Long donationId, Long volunteerId, String code) {
        DonateDonation peek = requireOwn(donationId, volunteerId);
        requireAwaiting(peek);
        String openid = identityService.openidOf(code);
        return DistributedLockSupport.runLocked(redissonClient, lockKeyOf(peek), () -> {
            DonateDonation d = requireOwn(donationId, volunteerId);
            requireAwaiting(d);
            return startTrade(d, openid);
        });
    }

    /** 本人取消待支付的捐款：先关交易单，关不掉（已付）就放弃。 */
    public void cancel(Long donationId, Long volunteerId) {
        DonateDonation peek = requireOwn(donationId, volunteerId);
        DistributedLockSupport.runLocked(redissonClient, lockKeyOf(peek), () -> {
            DonateDonation d = requireOwn(donationId, volunteerId);
            if (!Objects.equals(d.getStatus(), DonationFlow.AWAITING_PAYMENT)) {
                throw new BusinessException("只有待支付的捐款可以取消（当前：" + DonationFlow.statusLabel(d.getStatus()) + "）");
            }
            if (!tradeOrderService.closeByBiz(d.getBizType(), bizNoOf(d.getId()))) {
                throw new BusinessException("这笔捐款刚刚已付款，正在确认中，不能取消");
            }
            if (cancelAwaiting(d.getId(), "本人取消") != 1) {
                throw new BusinessException("这笔捐款的状态已变化，请刷新后重试");
            }
            return null;
        });
    }

    // ================= 付款回写 =================

    /**
     * 付款成功回写：待支付 → 已到账，并记进已筹金额。<b>幂等</b>，事件与补偿任务可能同时到。
     *
     * <p>在<b>独立事务</b>里执行（从交易提交后的事件回调里被调，加入已提交的外层事务等于写了不提交）。
     * 行锁顺序：捐款 → 结对登记 → 项目（与确认 / 取消结对一致）。</p>
     *
     * <p>结对已取消而钱到了：钱是真收了，照样记账（到账、已付、已筹），打 ERROR 等人工退款——
     * 拒绝记账不会让钱消失，只会让账对不上。正常路径到不了这里（取消前先关交易单）。</p>
     */
    public boolean onPaid(Integer bizType, String bizNo, Long tradeOrderId) {
        Long donationId = parseBizNo(bizNo);
        if (donationId == null) {
            log.error("[DONATION] 收到无法识别的捐款付款 bizType={} bizNo={} tradeOrderId={}", bizType, bizNo, tradeOrderId);
            return false;
        }
        Boolean moved = requiresNew.execute(s -> {
            LocalDateTime now = LocalDateTime.now();
            DonateDonation d = donationMapper.selectByIdForUpdate(donationId);
            if (d == null || !Objects.equals(d.getBizType(), bizType)) {
                log.error("[DONATION] 付款指向不存在的捐款 bizType={} donationId={} tradeOrderId={}",
                        bizType, donationId, tradeOrderId);
                return false;
            }
            int rows = donationMapper.update(null, Wrappers.<DonateDonation>lambdaUpdate()
                    .eq(DonateDonation::getId, donationId)
                    .eq(DonateDonation::getStatus, DonationFlow.AWAITING_PAYMENT)
                    .set(DonateDonation::getStatus, DonationFlow.PAID)
                    .set(DonateDonation::getTradeOrderId, tradeOrderId)
                    .set(DonateDonation::getPaidTime, now)
                    .set(DonateDonation::getInvoiceStatus, Objects.equals(d.getNeedInvoice(), 1)
                            ? DonationFlow.INVOICE_PENDING : DonationFlow.INVOICE_NONE)
                    .set(DonateDonation::getUpdateTime, now));
            if (rows != 1) {
                if (Objects.equals(d.getStatus(), DonationFlow.CANCELLED)) {
                    log.error("[DONATION] 已取消的捐款收到付款，需人工退款 donationId={} tradeOrderId={}",
                            donationId, tradeOrderId);
                }
                return false;
            }
            if (Objects.equals(bizType, DonationFlow.BIZ_PAIR)) {
                DonatePairRecord r = recordMapper.selectByIdForUpdate(d.getPairRecordId());
                recordMapper.addPaid(d.getPairRecordId(), d.getAmount(), now);
                if (r == null || Objects.equals(r.getStatus(), PairFlow.PAIR_CANCELLED)) {
                    log.error("[DONATION] 已取消的结对收到捐款，需人工退款 donationId={} pairRecordId={}",
                            donationId, d.getPairRecordId());
                }
                projectMapper.addRaised(d.getProjectId(), d.getAmount(), now);
            } else {
                crowdfundMapper.addRaised(d.getProjectId(), d.getAmount(), now);
            }
            return true;
        });
        return Boolean.TRUE.equals(moved);
    }

    /**
     * 补偿任务：处理所有待支付的捐款——事件丢了的推进，过了付款截止的取消。
     * <b>先问 trade 付了没有，再决定取消</b>；过期还挂着的交易单先以渠道为准查一次。
     */
    public int syncAwaitingDonations() {
        List<DonateDonation> awaiting = donationMapper.selectList(Wrappers.<DonateDonation>lambdaQuery()
                .eq(DonateDonation::getStatus, DonationFlow.AWAITING_PAYMENT)
                .orderByAsc(DonateDonation::getPayExpireTime)
                .last("LIMIT 200"));
        int handled = 0;
        for (DonateDonation d : awaiting) {
            try {
                if (syncOne(d)) {
                    handled++;
                }
            } catch (RuntimeException e) {
                log.error("[DONATION] 待支付捐款 {} 同步失败，下一轮再试", d.getId(), e);
            }
        }
        return handled;
    }

    private boolean syncOne(DonateDonation d) {
        String bizNo = bizNoOf(d.getId());
        TradeOrder trade = tradeOrderService.findLatestByBiz(d.getBizType(), bizNo);
        LocalDateTime now = LocalDateTime.now();
        if (trade != null && Objects.equals(trade.getStatus(), TradeFlow.ORDER_PENDING)
                && trade.getExpireTime() != null && !now.isBefore(trade.getExpireTime())) {
            tradeOrderService.queryAndSync(trade.getOutTradeNo(), TradeFlow.SOURCE_SCAN);
            trade = tradeOrderService.findLatestByBiz(d.getBizType(), bizNo);
        }
        if (trade != null && TradeFlow.isPaidLike(trade.getStatus())) {
            return onPaid(d.getBizType(), bizNo, trade.getId());
        }
        if (d.getPayExpireTime() == null || now.isBefore(d.getPayExpireTime())) {
            return false;
        }
        return Boolean.TRUE.equals(DistributedLockSupport.runLocked(redissonClient, lockKeyOf(d), () -> {
            if (!tradeOrderService.closeByBiz(d.getBizType(), bizNo)) {
                TradeOrder paid = tradeOrderService.findLatestByBiz(d.getBizType(), bizNo);
                return paid != null && onPaid(d.getBizType(), bizNo, paid.getId());
            }
            return cancelAwaiting(d.getId(), "超过付款时限未付款，已自动取消") == 1;
        }));
    }

    // ================= 结对撤回 / 取消时的捐款处置（PairService 调用） =================

    /**
     * 结对要被撤回或取消前：把这条结对的待支付捐款的交易单关掉。关不掉（已付）就抛出，让撤回 / 取消放弃。
     * <b>调用方须已持有 {@link #pairLockKey} 那把锁</b>。
     *
     * @return 那笔待支付捐款的 id；没有返回 null
     */
    Long closeAwaitingForPair(Long pairRecordId) {
        DonateDonation awaiting = donationMapper.selectOne(Wrappers.<DonateDonation>lambdaQuery()
                .eq(DonateDonation::getPairRecordId, pairRecordId)
                .eq(DonateDonation::getStatus, DonationFlow.AWAITING_PAYMENT)
                .last("LIMIT 1"));
        if (awaiting == null) {
            return null;
        }
        if (!tradeOrderService.closeByBiz(awaiting.getBizType(), bizNoOf(awaiting.getId()))) {
            throw new BusinessException("这条结对刚刚有一笔付款到账，请刷新后再操作");
        }
        return awaiting.getId();
    }

    /** 待支付 → 已取消（CAS）。可在调用方事务内调用。 */
    int cancelAwaiting(Long donationId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        return donationMapper.update(null, Wrappers.<DonateDonation>lambdaUpdate()
                .eq(DonateDonation::getId, donationId)
                .eq(DonateDonation::getStatus, DonationFlow.AWAITING_PAYMENT)
                .set(DonateDonation::getStatus, DonationFlow.CANCELLED)
                .set(DonateDonation::getCancelReason, cut(reason, 255))
                .set(DonateDonation::getUpdateTime, now));
    }

    /** 这条结对到账过钱没有（本人撤回前判断：付过钱的撤回要走协会取消 + 原路退款）。 */
    boolean hasPaid(Long pairRecordId) {
        return donationMapper.selectCount(Wrappers.<DonateDonation>lambdaQuery()
                .eq(DonateDonation::getPairRecordId, pairRecordId)
                .eq(DonateDonation::getStatus, DonationFlow.PAID)) > 0;
    }

    /** 这条结对所有已到账的捐款 id（协会取消结对后逐笔原路退款）。 */
    List<Long> paidIdsOfPair(Long pairRecordId) {
        return donationMapper.selectList(Wrappers.<DonateDonation>lambdaQuery()
                        .select(DonateDonation::getId)
                        .eq(DonateDonation::getPairRecordId, pairRecordId)
                        .eq(DonateDonation::getStatus, DonationFlow.PAID))
                .stream().map(DonateDonation::getId).toList();
    }

    static String pairLockKey(Long pairRecordId) {
        return LOCK_PREFIX + "pair:" + pairRecordId;
    }

    // ================= 退款与开票（后台） =================

    /**
     * 退一笔已到账的捐款：<b>先 CAS 已到账 → 已退款并减回已筹金额，提交之后再原路退款</b>；
     * 原路退款发起失败不回滚，原因落 {@code cash_refund_error}，到收付页按交易单重试。
     *
     * <p>已开票的不退：发票要先作废（红冲），这件事不在系统里，挡在这里让人先去处理。</p>
     */
    public void refund(Long donationId, String reason, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("请填写退款原因");
        }
        DonateDonation peek = requireById(donationId);
        DonateDonation refunded = DistributedLockSupport.runLocked(redissonClient, lockKeyOf(peek),
                () -> transactionTemplate.execute(s -> refundInTx(donationId, reason.trim(), adminId)));
        refundCash(refunded, reason.trim(), adminId);
    }

    /** 协会取消结对之后，逐笔退掉这条结对已到账的钱（PairService 在取消提交后调）。 */
    void refundAllOfPair(Long pairRecordId, String reason, Long adminId) {
        for (Long id : paidIdsOfPair(pairRecordId)) {
            try {
                refund(id, "结对已取消：" + reason, adminId);
            } catch (BusinessException e) {
                // 单笔失败不影响其它笔；那一笔仍是「已到账」，列表里看得见，可以单独再退
                log.error("[DONATION] 结对 {} 取消后退款 {} 未完成：{}", pairRecordId, id, e.getMessage());
            }
        }
    }

    private DonateDonation refundInTx(Long donationId, String reason, Long adminId) {
        DonateDonation d = donationMapper.selectByIdForUpdate(donationId);
        if (d == null) {
            throw new BusinessException("捐款记录不存在");
        }
        if (!Objects.equals(d.getStatus(), DonationFlow.PAID)) {
            throw new BusinessException("只有已到账的捐款可以退款（当前：" + DonationFlow.statusLabel(d.getStatus()) + "）");
        }
        if (Objects.equals(d.getInvoiceStatus(), DonationFlow.INVOICE_ISSUED)) {
            throw new BusinessException("这笔捐款已开票，退款前请先作废发票");
        }
        LocalDateTime now = LocalDateTime.now();
        donationMapper.update(null, Wrappers.<DonateDonation>lambdaUpdate()
                .eq(DonateDonation::getId, donationId)
                .eq(DonateDonation::getStatus, DonationFlow.PAID)
                .set(DonateDonation::getStatus, DonationFlow.REFUNDED)
                .set(DonateDonation::getRefundTime, now)
                .set(DonateDonation::getRefundBy, adminId)
                .set(DonateDonation::getRefundReason, cut(reason, 512))
                .set(DonateDonation::getUpdateTime, now));
        if (Objects.equals(d.getBizType(), DonationFlow.BIZ_PAIR)) {
            if (recordMapper.subtractPaid(d.getPairRecordId(), d.getAmount(), now) != 1
                    || projectMapper.subtractRaised(d.getProjectId(), d.getAmount(), now) != 1) {
                throw new BusinessException("结对已付 / 项目已到账金额异常（回退后会为负），请联系管理员核查");
            }
        } else if (crowdfundMapper.subtractRaised(d.getProjectId(), d.getAmount(), now) != 1) {
            throw new BusinessException("项目已筹金额异常（回退后会为负），请联系管理员核查");
        }
        return d;
    }

    private void refundCash(DonateDonation d, String reason, Long adminId) {
        Long tradeOrderId = d.getTradeOrderId();
        if (tradeOrderId == null) {
            TradeOrder t = tradeOrderService.findLatestByBiz(d.getBizType(), bizNoOf(d.getId()));
            tradeOrderId = t != null && TradeFlow.isPaidLike(t.getStatus()) ? t.getId() : null;
        }
        String refundNo = null;
        String error = null;
        if (tradeOrderId == null) {
            error = "找不到已支付的交易单，无法自动退款";
        } else {
            TradeDTOs.Refund dto = new TradeDTOs.Refund();
            dto.setReason(cut("捐款 " + d.getDonationNo() + " 退款：" + reason, 512));
            try {
                refundNo = tradeRefundService.refund(tradeOrderId, dto, adminId);
            } catch (BusinessException e) {
                error = e.getMessage();
            }
        }
        if (error != null) {
            log.error("[DONATION] 捐款 {} 已标记退款，但原路退款未发起：{}（交易单 {}）", d.getId(), error, tradeOrderId);
        }
        donationMapper.update(null, Wrappers.<DonateDonation>lambdaUpdate()
                .eq(DonateDonation::getId, d.getId())
                .set(DonateDonation::getTradeOrderId, tradeOrderId)
                .set(DonateDonation::getCashRefundNo, refundNo)
                .set(DonateDonation::getCashRefundError, error == null ? null : cut(error, 255))
                .set(DonateDonation::getUpdateTime, LocalDateTime.now()));
    }

    /** 登记开票（清单⑦只预留：发票在系统外开，这里记发票号）。仅已到账且需要发票的。 */
    public void markInvoiced(Long donationId, String invoiceNo, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        if (!StringUtils.hasText(invoiceNo)) {
            throw new BusinessException("请填写发票号");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = donationMapper.update(null, Wrappers.<DonateDonation>lambdaUpdate()
                .eq(DonateDonation::getId, donationId)
                .eq(DonateDonation::getStatus, DonationFlow.PAID)
                .eq(DonateDonation::getInvoiceStatus, DonationFlow.INVOICE_PENDING)
                .set(DonateDonation::getInvoiceStatus, DonationFlow.INVOICE_ISSUED)
                .set(DonateDonation::getInvoiceNo, invoiceNo.trim())
                .set(DonateDonation::getInvoiceTime, now)
                .set(DonateDonation::getInvoiceBy, adminId)
                .set(DonateDonation::getUpdateTime, now));
        if (rows != 1) {
            DonateDonation d = requireById(donationId);
            throw new BusinessException("这笔捐款当前不能登记开票（" + DonationFlow.statusLabel(d.getStatus()) + " · "
                    + DonationFlow.invoiceLabel(d.getInvoiceStatus()) + "）");
        }
    }

    // ================= 查询 =================

    /** 某条结对登记下的全部捐款（结对中心详情用；调用方负责确认这条结对是本人的），新的在前。 */
    public List<DonationVOs.Donation> listOfPairRecord(Long pairRecordId) {
        return donationMapper.selectList(Wrappers.<DonateDonation>lambdaQuery()
                        .eq(DonateDonation::getBizType, DonationFlow.BIZ_PAIR)
                        .eq(DonateDonation::getPairRecordId, pairRecordId)
                        .orderByDesc(DonateDonation::getId))
                .stream().map(d -> toVO(d, false)).toList();
    }

    public DonationVOs.Donation detailMine(Long donationId, Long volunteerId) {
        return toVO(requireOwn(donationId, volunteerId), false);
    }

    /** 后台捐款列表。keyword 试单号、项目名、捐款人（姓名模糊 / 手机号精确）。 */
    public PageResult<DonationVOs.Donation> listForAdmin(PageQuery query, Integer bizType, Long projectId,
                                                         Integer status, Integer invoiceStatus, String keyword) {
        List<Long> volunteerIds = StringUtils.hasText(keyword)
                ? volunteerQueryService.findIdsByNameOrPhone(keyword.trim(), 200) : List.of();
        IPage<DonateDonation> page = donationMapper.selectPage(query.toPage(), Wrappers.<DonateDonation>lambdaQuery()
                .eq(bizType != null, DonateDonation::getBizType, bizType)
                .eq(projectId != null, DonateDonation::getProjectId, projectId)
                .eq(status != null, DonateDonation::getStatus, status)
                .eq(invoiceStatus != null, DonateDonation::getInvoiceStatus, invoiceStatus)
                .and(StringUtils.hasText(keyword), w -> {
                    w.like(DonateDonation::getDonationNo, keyword.trim())
                            .or().like(DonateDonation::getProjectTitle, keyword.trim());
                    if (!volunteerIds.isEmpty()) {
                        w.or().in(DonateDonation::getVolunteerId, volunteerIds);
                    }
                })
                .orderByDesc(DonateDonation::getId));
        Map<Long, String> names = volunteerQueryService.listNamesByIds(page.getRecords().stream()
                .map(DonateDonation::getVolunteerId).collect(Collectors.toSet()));
        return PageResult.of(page.convert(d -> {
            DonationVOs.Donation vo = toVO(d, true);
            vo.setVolunteerName(names.get(d.getVolunteerId()));
            return vo;
        }));
    }

    /** 项目捐赠记录（公开）：只列已到账的，姓名打码。 */
    public PageResult<DonationVOs.PublicRecord> publicRecords(int bizType, Long projectId, PageQuery query) {
        IPage<DonateDonation> page = donationMapper.selectPage(query.toPage(), Wrappers.<DonateDonation>lambdaQuery()
                .eq(DonateDonation::getBizType, bizType)
                .eq(DonateDonation::getProjectId, projectId)
                .eq(DonateDonation::getStatus, DonationFlow.PAID)
                .orderByDesc(DonateDonation::getPaidTime));
        Map<Long, String> names = volunteerQueryService.listNamesByIds(page.getRecords().stream()
                .map(DonateDonation::getVolunteerId).collect(Collectors.toSet()));
        return PageResult.of(page.convert(d -> {
            DonationVOs.PublicRecord vo = new DonationVOs.PublicRecord();
            vo.setDonorName(mask(names.get(d.getVolunteerId())));
            vo.setAmount(d.getAmount());
            vo.setRemark(d.getRemark());
            vo.setPaidTime(d.getPaidTime());
            return vo;
        }));
    }

    /** 各项目的捐款人数（已到账、去重），供众筹列表批量取。 */
    public Map<Long, Integer> donorCounts(int bizType, Collection<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> out = new HashMap<>();
        for (Map<String, Object> row : donationMapper.countDonors(bizType, projectIds, DonationFlow.PAID)) {
            Object pid = row.get("projectId");
            Object cnt = row.get("cnt");
            if (pid != null && cnt != null) {
                out.put(((Number) pid).longValue(), ((Number) cnt).intValue());
            }
        }
        return out;
    }

    // ================= 内部 =================

    private DonationVOs.Created created(DonateDonation d, TradeVOs.Prepay prepay) {
        DonationVOs.Created c = new DonationVOs.Created();
        c.setDonation(toVO(donationMapper.selectById(d.getId()), false));
        c.setPrepay(prepay);
        return c;
    }

    /**
     * 让 trade 下单。失败（渠道拒绝、网络）时把这笔待支付取消，不留一笔永远付不了的记录，再把错误抛出去。
     */
    private TradeVOs.Prepay startTrade(DonateDonation d, String openid) {
        TradeDTOs.CreateOrder dto = new TradeDTOs.CreateOrder();
        dto.setBizType(d.getBizType());
        dto.setBizNo(bizNoOf(d.getId()));
        dto.setVolunteerId(d.getVolunteerId());
        dto.setPayerOpenid(openid);
        dto.setSubject(cut(DonationFlow.bizLabel(d.getBizType()) + "·" + d.getProjectTitle(), 128));
        dto.setAmountFen(d.getAmountFen());
        dto.setExpireAt(d.getPayExpireTime());
        dto.setRemark("捐款 " + d.getDonationNo());
        try {
            return tradeOrderService.createOrder(dto);
        } catch (RuntimeException e) {
            if (tradeOrderService.closeByBiz(d.getBizType(), bizNoOf(d.getId()))) {
                cancelAwaiting(d.getId(), "发起支付失败：" + e.getMessage());
            }
            throw e instanceof BusinessException be ? be : new BusinessException("发起支付失败，请重试");
        }
    }

    private DonateDonation newDonation(int bizType, Long projectId, Long pairRecordId, Long volunteerId, String title,
                                       BigDecimal amount, String remark, Invoice invoice, LocalDateTime now) {
        DonateDonation d = new DonateDonation();
        d.setDonationNo("JK" + now.format(NO_FMT) + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000)));
        d.setBizType(bizType);
        d.setProjectId(projectId);
        d.setPairRecordId(pairRecordId);
        d.setVolunteerId(volunteerId);
        d.setProjectTitle(cut(title, 128));
        d.setAmount(amount);
        d.setAmountFen(amount.movePointRight(2).intValueExact());
        d.setStatus(DonationFlow.AWAITING_PAYMENT);
        d.setRemark(StringUtils.hasText(remark) ? cut(remark.trim(), 255) : null);
        d.setPayExpireTime(now.plusMinutes(Math.max(1, properties.getPayTimeoutMinutes())).withNano(0));
        d.setNeedInvoice(invoice.need() ? 1 : 0);
        d.setInvoiceTitle(invoice.title());
        d.setInvoiceTaxNo(invoice.taxNo());
        d.setInvoiceStatus(DonationFlow.INVOICE_NONE);
        return d;
    }

    private record Invoice(boolean need, String title, String taxNo) {
    }

    /** 需要发票就必须有抬头；不需要却填了抬头 / 税号，报错而不是静默清空。 */
    private static Invoice checkInvoice(DonationDTOs.InvoiceFields f) {
        boolean need = Boolean.TRUE.equals(f.getNeedInvoice());
        String title = StringUtils.hasText(f.getInvoiceTitle()) ? f.getInvoiceTitle().trim() : null;
        String taxNo = StringUtils.hasText(f.getInvoiceTaxNo()) ? f.getInvoiceTaxNo().trim() : null;
        if (need && title == null) {
            throw new BusinessException("需要发票请填写发票抬头");
        }
        if (!need && (title != null || taxNo != null)) {
            throw new BusinessException("不需要发票时不用填写抬头与税号");
        }
        return new Invoice(need, title, taxNo);
    }

    private BigDecimal checkAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("捐款金额须大于 0");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BusinessException("捐款金额最多两位小数");
        }
        if (properties.getMaxAmount() != null && amount.compareTo(properties.getMaxAmount()) > 0) {
            throw new BusinessException("单笔捐款不超过 " + properties.getMaxAmount().toPlainString()
                    + " 元，更大的金额请联系协会走对公转账");
        }
        return amount.setScale(2);
    }

    private void requirePaymentEnabled() {
        if (!tradeOrderService.paymentEnabled()) {
            throw new BusinessException("微信支付尚未开通，暂时无法在线捐款");
        }
    }

    private static void requireVolunteer(Long volunteerId) {
        if (volunteerId == null) {
            throw new BusinessException("志愿者不能为空");
        }
    }

    private DonateDonation requireById(Long id) {
        DonateDonation d = id == null ? null : donationMapper.selectById(id);
        if (d == null) {
            throw new BusinessException("捐款记录不存在");
        }
        return d;
    }

    private DonateDonation requireOwn(Long donationId, Long volunteerId) {
        DonateDonation d = donationId == null ? null : donationMapper.selectById(donationId);
        if (d == null || !Objects.equals(d.getVolunteerId(), volunteerId)) {
            throw new BusinessException("捐款记录不存在");
        }
        return d;
    }

    private static void requireAwaiting(DonateDonation d) {
        if (!Objects.equals(d.getStatus(), DonationFlow.AWAITING_PAYMENT)) {
            throw new BusinessException("这笔捐款不需要付款（当前：" + DonationFlow.statusLabel(d.getStatus()) + "）");
        }
        if (d.getPayExpireTime() != null && !LocalDateTime.now().isBefore(d.getPayExpireTime())) {
            throw new BusinessException("已超过付款时限，这笔捐款会自动取消，请重新发起");
        }
    }

    static String lockKeyOf(DonateDonation d) {
        return d.getPairRecordId() != null ? pairLockKey(d.getPairRecordId()) : LOCK_PREFIX + "d:" + d.getId();
    }

    static String bizNoOf(Long donationId) {
        return String.valueOf(donationId);
    }

    private static Long parseBizNo(String bizNo) {
        try {
            return bizNo == null ? null : Long.valueOf(bizNo.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 姓名打码：保留第一个字，其余换成 *（至少一个 *）。 */
    static String mask(String name) {
        if (!StringUtils.hasText(name)) {
            return "爱心人士";
        }
        String n = name.trim();
        int first = n.codePointAt(0);
        int rest = Math.max(1, n.codePointCount(0, n.length()) - 1);
        return new String(Character.toChars(first)) + "*".repeat(rest);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private DonationVOs.Donation toVO(DonateDonation d, boolean forAdmin) {
        DonationVOs.Donation vo = new DonationVOs.Donation();
        vo.setId(d.getId());
        vo.setDonationNo(d.getDonationNo());
        vo.setBizType(d.getBizType());
        vo.setBizTypeLabel(DonationFlow.bizLabel(d.getBizType()));
        vo.setProjectId(d.getProjectId());
        vo.setProjectTitle(d.getProjectTitle());
        vo.setPairRecordId(d.getPairRecordId());
        vo.setAmount(d.getAmount());
        vo.setStatus(d.getStatus());
        vo.setStatusLabel(DonationFlow.statusLabel(d.getStatus()));
        vo.setRemark(d.getRemark());
        vo.setPayExpireTime(d.getPayExpireTime());
        vo.setPaidTime(d.getPaidTime());
        vo.setCancelReason(d.getCancelReason());
        vo.setNeedInvoice(Objects.equals(d.getNeedInvoice(), 1));
        vo.setInvoiceTitle(d.getInvoiceTitle());
        vo.setInvoiceTaxNo(d.getInvoiceTaxNo());
        vo.setInvoiceStatus(d.getInvoiceStatus());
        vo.setInvoiceStatusLabel(DonationFlow.invoiceLabel(d.getInvoiceStatus()));
        vo.setInvoiceNo(d.getInvoiceNo());
        vo.setRefundTime(d.getRefundTime());
        vo.setRefundReason(d.getRefundReason());
        vo.setCreateTime(d.getCreateTime());
        if (forAdmin) {
            vo.setVolunteerId(d.getVolunteerId());
            vo.setTradeOrderId(d.getTradeOrderId());
            vo.setCashRefundNo(d.getCashRefundNo());
            vo.setCashRefundError(d.getCashRefundError());
        }
        return vo;
    }
}
