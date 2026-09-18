package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.service.PointService;
import com.hengde.auth.service.MiniappIdentityService;
import com.hengde.auth.service.SmsNotifyService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.pickup.PickupCodeUtil;
import com.hengde.common.sms.SmsNotifyTemplate;
import com.hengde.donate.config.MallProperties;
import com.hengde.donate.constant.MallDeliveryType;
import com.hengde.donate.vo.SponsorOrderVO;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.constant.ExpressCompany;
import com.hengde.donate.constant.MallOrderStatus;
import com.hengde.donate.constant.MallShippingPayType;
import com.hengde.donate.constant.PickupOperatorType;
import com.hengde.donate.dao.MallGoodsMapper;
import com.hengde.donate.dao.MallGoodsReviewMapper;
import com.hengde.donate.dao.MallGoodsSpecMapper;
import com.hengde.donate.dao.MallOrderMapper;
import com.hengde.donate.entity.MallGoods;
import com.hengde.donate.entity.MallGoodsReview;
import com.hengde.donate.entity.MallGoodsSpec;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.entity.MallVerifier;
import com.hengde.donate.dto.ExpressDTO;
import com.hengde.donate.dto.MallOrderPlaceDTO;
import com.hengde.donate.vo.ExchangeRecordVO;
import com.hengde.donate.vo.MallOrderVO;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 积分兑换单（Row 8）。
 *
 * <p><b>下单即扣分、驳回/取消退分</b>——与奖惩「审核通过才入账」<b>刻意相反</b>：
 * 那里是罚，这里是用户主动消费，积分与库存都必须在下单那一刻占位，
 * 否则同一笔积分能下十张待审单。<b>这条差异是有意的，日后不要为了「统一」而改掉。</b>
 * 卷同理：<b>下单即占用、退单即归还</b>，否则同一张卷能挂在十张待审单上。</p>
 *
 * <p><b>一单一件（数量恒为 1）</b>是本批的显式取舍，不是漏做——理由与影响见
 * {@code 文档/v3/V3规划.md} 的「商城批」一节。要加数量时，库存 CAS 的
 * {@code stock - 1}/{@code stock + 1}、订单表、D8 的快照、退分金额<b>四处必须同时改</b>，
 * 而只有 SQL 里那个 {@code 1} 会在改的时候撞到眼前。卷批之后还要加第五处：满减卷的门槛是按单价比、还是按总价比。</p>
 *
 * <p><b>{@code points} 从卷批（V48）起专指实际扣分</b>，{@code original_points} 是标价快照。
 * 退分一律按 {@code points} 退——它才与 {@code point_record} 对得上。商城快递批起，
 * 积分抵扣的快递费也<b>并进</b>这个数（{@code shipping_points} 是其中的快递费部分）。</p>
 *
 * <p><b>商城快递批：现金与快递</b>。有现金要付的单（商品带现金部分，或快递费选了现金）下单后落<b>待支付</b>，
 * 积分、库存、卷<b>同样在下单那一刻占住</b>；付款成功推进到待审核，超过付款截止没付的由补偿任务取消并全部归还。
 * 与「钱」有关的三条规矩：</p>
 * <ul>
 *   <li><b>取消与付款不能两边都赢</b>：取消先让 trade 关掉交易单（{@code closeByBiz}），关不掉（已经付了）就放弃取消；
 *       发起付款与取消<b>持同一把志愿者锁</b>，不会出现「刚关掉旧交易单、付款又建了一张新的」；</li>
 *   <li><b>付款成功的推进不只靠事件</b>：{@code TradePaidEvent} 只发一次、不持久，
 *       {@link #syncAwaitingPayments} 定期拿待支付的单去问 trade，已付的照样推进；</li>
 *   <li><b>已付款的单被驳回</b>：先 CAS 驳回并归还积分库存卷，<b>再</b>发起原路退款——反过来，退款成功而驳回 CAS 输给了并发的通过，
 *       就成了「钱退了、东西照发」。退款发起失败记在订单上，后台据此到收付页重试。</li>
 * </ul>
 *
 * @author hengde
 */
@Slf4j
@Service
public class MallOrderService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 取货码撞唯一键时的换号重试上限。 */
    private static final int CODE_RETRY = 5;

    private MallOrderMapper orderMapper;
    private MallGoodsMapper goodsMapper;
    private MallGoodsSpecMapper specMapper;
    private MallGoodsReviewMapper reviewMapper;
    private PointService pointService;
    private VolunteerQueryService volunteerQueryService;
    private MallProperties mallProperties;
    private RedissonClient redissonClient;
    private TransactionTemplate transactionTemplate;
    private MallCouponService couponService;
    private MallVerifierService verifierService;
    private SmsNotifyService smsNotifyService;
    private MallShippingCalculator shippingCalculator;
    private TradeOrderService tradeOrderService;
    private TradeRefundService tradeRefundService;
    private MiniappIdentityService identityService;
    private CryptoUtil cryptoUtil;
    /** 付款成功回写用的独立事务：它在交易提交之后的事件回调里跑，不能加入（已提交的）外层事务 */
    private TransactionTemplate requiresNew;

    @Autowired
    public void setShippingCalculator(MallShippingCalculator shippingCalculator) {
        this.shippingCalculator = shippingCalculator;
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
    public void setCryptoUtil(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Autowired
    public void setReviewMapper(MallGoodsReviewMapper reviewMapper) {
        this.reviewMapper = reviewMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setMallProperties(MallProperties mallProperties) {
        this.mallProperties = mallProperties;
    }

    @Autowired
    public void setOrderMapper(MallOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Autowired
    public void setGoodsMapper(MallGoodsMapper goodsMapper) {
        this.goodsMapper = goodsMapper;
    }

    @Autowired
    public void setSpecMapper(MallGoodsSpecMapper specMapper) {
        this.specMapper = specMapper;
    }

    @Autowired
    public void setPointService(PointService pointService) {
        this.pointService = pointService;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setTransactionTemplate(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    @Autowired
    public void setCouponService(MallCouponService couponService) {
        this.couponService = couponService;
    }

    @Autowired
    public void setVerifierService(MallVerifierService verifierService) {
        this.verifierService = verifierService;
    }

    @Autowired
    public void setSmsNotifyService(SmsNotifyService smsNotifyService) {
        this.smsNotifyService = smsNotifyService;
    }

    /** 下单兑换（不用卷）。 */
    public MallOrder placeOrder(Long volunteerId, Long specId) {
        return placeOrder(volunteerId, specId, null);
    }

    /**
     * 下单兑换，可选用一张卷。
     *
     * <p><b>锁在事务之外获取、包住整个事务</b>（项目既有约定），且用的是
     * {@link PointService#LOCK_KEY_PREFIX} <b>同一把锁</b>——手工扣分与兑换扣分必须互斥，
     * 两者都要「先看余额、再扣」，各锁各的会让余额检查同时通过、把余额扣成负数。</p>
     *
     * <p><b>顺序是承重的：先扣库存，再读快照，再判卷，然后落单、占卷，最后扣分。</b></p>
     * <ol>
     *   <li><b>扣库存的 CAS 同时校验了商品可下单</b>（上架 / 未隐藏 / 未删 / 规格未删 / 有货），
     *       一条语句解决，不产生「先读商品再改规格」那个锁升级序列；</li>
     *   <li><b>读快照放在扣减之后</b>：此时本事务已持有该规格行的排他锁，读到的就是当前值，
     *       不会把一个刚被改过的旧价格快照进订单；</li>
     *   <li><b>判卷只读、占卷走 CAS</b>：预检给出准确文案，CAS（{@code MallCouponGrantMapper.useGrant}）
     *       才是「一张卷只能用一次」的来源——两者用同一个「现在」，对过没过期的判断不会打架；</li>
     *   <li>扣分放最后，与订单同事务——积分与业务变更同成同败。实付为 0（兑换卷）时<b>不记流水</b>：
     *       账本不收 0 分的流水，而这张单也确实没有花掉积分。</li>
     * </ol>
     *
     * @param volunteerId   志愿者 id
     * @param specId        规格 id
     * @param couponGrantId 要用的卷发放记录 id，可空
     * @return 落库后的订单
     */
    public MallOrder placeOrder(Long volunteerId, Long specId, Long couponGrantId) {
        MallOrderPlaceDTO req = new MallOrderPlaceDTO();
        req.setSpecId(specId);
        req.setCouponGrantId(couponGrantId);
        return placeOrder(volunteerId, req);
    }

    /**
     * 下单（商城快递批起的完整入口）：领取方式、快递费支付方式、收件信息都在这里。
     *
     * <p><b>入参校验在锁与事务之外</b>：填错一个手机号不该去排一次志愿者锁。
     * <b>填了不该填的报错、不静默清空</b>——自提单带着收件地址过来，多半是前端把选项弄错了。</p>
     */
    public MallOrder placeOrder(Long volunteerId, MallOrderPlaceDTO req) {
        if (volunteerId == null) {
            throw new BusinessException("志愿者不能为空");
        }
        if (req == null || req.getSpecId() == null) {
            throw new BusinessException("请选择商品规格");
        }
        int delivery = req.getDeliveryType() == null ? MallDeliveryType.PICKUP : req.getDeliveryType();
        if (!MallDeliveryType.isValid(delivery)) {
            throw new BusinessException("领取方式不正确");
        }
        if (delivery == MallDeliveryType.EXPRESS) {
            if (!shippingCalculator.expressEnabled()) {
                throw new BusinessException("暂不支持快递寄送，请选择自提");
            }
            if (!MallShippingPayType.isValid(req.getShippingPayType())) {
                throw new BusinessException("请选择快递费的支付方式（现金或积分抵扣）");
            }
            if (!StringUtils.hasText(req.getRecvName()) || !StringUtils.hasText(req.getRecvPhone())
                    || !StringUtils.hasText(req.getRecvAddress())) {
                throw new BusinessException("快递寄送请填写收件人、电话与地址");
            }
            if (!req.getRecvPhone().trim().matches("1\\d{10}")) {
                throw new BusinessException("收件电话请填写 11 位手机号");
            }
            if (req.getRecvName().trim().length() > 64 || req.getRecvAddress().trim().length() > 255) {
                throw new BusinessException("收件人或地址过长");
            }
        } else if (req.getShippingPayType() != null || StringUtils.hasText(req.getRecvName())
                || StringUtils.hasText(req.getRecvPhone()) || StringUtils.hasText(req.getRecvAddress())) {
            throw new BusinessException("自提不需要填写快递费支付方式与收件信息");
        }
        return DistributedLockSupport.runLocked(redissonClient,
                PointService.LOCK_KEY_PREFIX + volunteerId,
                () -> transactionTemplate.execute(status -> doPlaceOrder(volunteerId, req, delivery)));
    }

    private MallOrder doPlaceOrder(Long volunteerId, MallOrderPlaceDTO req, int delivery) {
        Long specId = req.getSpecId();
        Long couponGrantId = req.getCouponGrantId();
        // ① 扣库存 + 校验商品可下单，一条 CAS
        if (specMapper.deductStock(specId, MallGoodsStatus.ON_SALE) != 1) {
            throw new BusinessException(explainDeductFailure(specId));
        }
        // ② 扣减成功后再读，拿到的是当前值（本事务已持有该行排他锁）
        MallGoodsSpec spec = specMapper.selectById(specId);
        MallGoods goods = goodsMapper.selectById(spec.getGoodsId());
        int price = spec.getPoints() == null ? 0 : spec.getPoints();
        if (price <= 0) {
            throw new BusinessException("该规格未设置所需积分");
        }
        // ③ 判卷（只读）：没带卷而商品要求卷，当场拒绝
        LocalDateTime now = LocalDateTime.now();
        MallCouponService.CouponQuote quote = null;
        if (couponGrantId != null) {
            quote = couponService.quote(volunteerId, couponGrantId, goods, price, now);
        } else if (goods != null && goods.getRequireCouponId() != null) {
            throw new BusinessException("该商品只能使用「" + couponService.nameOf(goods.getRequireCouponId())
                    + "」兑换，请先选择卷");
        }
        int goodsPay = quote == null ? price : quote.payPoints();
        // ③' 快递费与现金：计价器算快递费（快照），积分抵扣的折成积分并进扣分，现金的并进应付现金
        int fee = shippingCalculator.feeFen(delivery, goods, spec);
        int rate = shippingCalculator.pointsPerYuan();
        Integer shippingPayType = delivery == MallDeliveryType.EXPRESS ? req.getShippingPayType() : null;
        int shippingPoints = shippingPayType != null && shippingPayType == MallShippingPayType.POINTS
                ? MallShippingCalculator.pointsFor(fee, rate) : 0;
        int goodsCash = spec.getCashFen() == null ? 0 : spec.getCashFen();
        int payCash = goodsCash + (shippingPayType != null && shippingPayType == MallShippingPayType.CASH ? fee : 0);
        if (payCash > 0 && !tradeOrderService.paymentEnabled()) {
            // 先拦在这里：付不了的单占着库存与积分等超时，只会让别人也兑不到
            throw new BusinessException(goodsCash > 0
                    ? "这件商品需要同时支付现金，微信支付尚未开通，暂时无法兑换"
                    : "微信支付尚未开通，快递费请选择积分抵扣或改为自提");
        }
        int pay = goodsPay + shippingPoints;
        // ④ 余额检查与手工扣分共用同一处实现；本方法已在同一把锁内
        if (pay > 0) {
            pointService.assertDeductible(volunteerId, -pay);
        }

        MallOrder order = new MallOrder();
        order.setOrderNo(nextOrderNo());
        order.setVolunteerId(volunteerId);
        order.setGoodsId(spec.getGoodsId());
        order.setSpecId(specId);
        // 快照：规格可改可删、卷可被作废，事后要还原得出「当时买的是什么、标价多少、用了什么卷、实付多少」
        order.setGoodsName(goods == null ? "" : goods.getName());
        order.setSpecName(spec.getName());
        order.setOriginalPoints(price);
        order.setPoints(pay);
        if (quote != null) {
            order.setCouponGrantId(couponGrantId);
            order.setCouponName(quote.grant().getCouponName());
            order.setCouponDeductPoints(quote.deductPoints());
        }
        order.setDeliveryType(delivery);
        order.setGoodsCashFen(goodsCash);
        order.setShippingFeeFen(fee);
        order.setShippingPayType(shippingPayType);
        order.setPointsPerYuan(delivery == MallDeliveryType.EXPRESS ? rate : null);
        order.setShippingPoints(shippingPoints);
        order.setPayCashFen(payCash);
        if (delivery == MallDeliveryType.EXPRESS) {
            order.setRecvName(req.getRecvName().trim());
            order.setRecvPhone(cryptoUtil.encrypt(req.getRecvPhone().trim()));
            order.setRecvAddress(req.getRecvAddress().trim());
        }
        if (payCash > 0) {
            order.setStatus(MallOrderStatus.AWAITING_PAYMENT);
            order.setPayExpireTime(now.plusMinutes(Math.max(1, mallProperties.getPayment().getTimeoutMinutes()))
                    .withNano(0));
        } else {
            order.setStatus(MallOrderStatus.PENDING);
        }
        orderMapper.insert(order);

        // ⑤ 占卷：预检之后到这里之间，卷可能已被别的请求用掉 / 作废 / 恰好到期——CAS 失败就整单回滚
        if (quote != null && !couponService.use(couponGrantId, volunteerId, order.getId(), now)) {
            throw new BusinessException("这张卷刚刚已不可用，请重新选择");
        }

        // ⑥ 扣分与订单同事务；source_id = 订单 id，uk_source 保幂等
        if (pay > 0) {
            String remark = "兑换 " + order.getGoodsName() + "（" + order.getSpecName() + "）"
                    + (quote == null ? "" : "，用卷「" + order.getCouponName() + "」抵 " + quote.deductPoints() + " 分")
                    + (shippingPoints > 0 ? "，积分抵扣快递费 " + shippingPoints + " 分" : "");
            pointService.record(volunteerId, -pay, PointSourceType.EXCHANGE, order.getId(), remark,
                    PointSourceType.OPERATOR_SYSTEM, null);
        }
        return order;
    }

    /**
     * 库存 CAS 影响行数为 0 时，补一次只读查询给出准确文案。
     *
     * <p>拒绝的原因有五种，一律报「库存不足」会让「商品刚被下架」看起来像「卖光了」。</p>
     */
    private String explainDeductFailure(Long specId) {
        MallGoodsSpec spec = specMapper.selectById(specId);
        if (spec == null) {
            return "商品规格不存在";
        }
        MallGoods goods = goodsMapper.selectById(spec.getGoodsId());
        if (goods == null) {
            return "商品不存在";
        }
        if (goods.getStatus() == null || goods.getStatus() != MallGoodsStatus.ON_SALE) {
            return "该商品已下架";
        }
        if (goods.getHidden() != null && goods.getHidden() == 1) {
            return "该商品已下架";
        }
        if (goods.getSponsorSuspended() != null && goods.getSponsorSuspended() == 1) {
            // V78：赞助企业被暂停 / 删除。不说的话会落到下面那句「库存不足」，人会以为等补货就行
            return "该商品的赞助企业暂停合作，暂时不能兑换";
        }
        return "该规格库存不足";
    }

    /**
     * 取消兑换：退分 + 还库存 + 还卷。
     *
     * <p><b>幂等靠订单状态的 CAS</b>：只有把「待审核」原子地改成「已取消」成功的那一次
     * 才会往下走，双击取消只会退一次。<b>退分、还库存、还卷必须同事务</b>——
     * 只还其一都是账实分离。</p>
     *
     * <p>退分复用 {@code EXCHANGE} 来源码记一笔<b>正数</b>，靠
     * {@code sys:mall-refund:} 前缀的 requestId 保幂等——理由是<b>口径</b>：
     * 换一个非消费类来源码会让「已使用积分」与「累计获得」同时算错。</p>
     *
     * <p>志愿者自己取消<b>不发短信</b>：自己刚点完的事，不必再花一条短信告诉他。</p>
     */
    public void cancel(Long orderId, Long volunteerId) {
        if (orderId == null || volunteerId == null) {
            throw new BusinessException("参数不完整");
        }
        DistributedLockSupport.runLocked(redissonClient,
                PointService.LOCK_KEY_PREFIX + volunteerId, () -> {
                    MallOrder order = orderMapper.selectById(orderId);
                    if (order == null || !order.getVolunteerId().equals(volunteerId)) {
                        throw new BusinessException("兑换单不存在");
                    }
                    int from = MallOrderStatus.PENDING;
                    if (order.getStatus() != null && order.getStatus() == MallOrderStatus.AWAITING_PAYMENT) {
                        // 先让 trade 关掉交易单：关不掉就是已经付了，必须放弃取消（否则钱收了、单子没了）
                        if (!tradeOrderService.closeByBiz(TradeFlow.BIZ_MALL_SHIPPING, bizNoOf(orderId))) {
                            throw new BusinessException("这张兑换单刚刚已付款，正在确认中，暂不能取消");
                        }
                        from = MallOrderStatus.AWAITING_PAYMENT;
                    } else if (isPaidCashOrder(order)) {
                        // 已付款的单退现金要走原路退款，发起人须是后台（trade 的退款要记操作人）；
                        // 让志愿者一点就把钱退出去，等于把「谁决定退款」交给了一个按钮
                        throw new BusinessException("已付款的兑换单请联系协会取消，款项会原路退回");
                    }
                    int fromStatus = from;
                    transactionTemplate.execute(s -> {
                        refundOrder(orderId, volunteerId, fromStatus, MallOrderStatus.CANCELLED, null, null);
                        return null;
                    });
                    return null;
                });
    }

    /** 后台驳回：退分 + 还库存 + 还卷，记原因与审核人，短信告知（事务提交后才发）。 */
    public void reject(Long orderId, String reason, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        MallOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("兑换单不存在");
        }
        MallOrder rejected = DistributedLockSupport.runLocked(redissonClient,
                PointService.LOCK_KEY_PREFIX + order.getVolunteerId(), () -> {
                    MallOrder current = orderMapper.selectById(orderId);
                    int from = MallOrderStatus.PENDING;
                    if (current != null && current.getStatus() != null
                            && current.getStatus() == MallOrderStatus.AWAITING_PAYMENT) {
                        if (!tradeOrderService.closeByBiz(TradeFlow.BIZ_MALL_SHIPPING, bizNoOf(orderId))) {
                            throw new BusinessException("该兑换单刚刚付款成功，请刷新后再审核");
                        }
                        from = MallOrderStatus.AWAITING_PAYMENT;
                    }
                    int fromStatus = from;
                    return transactionTemplate.execute(s -> {
                        MallOrder snap = refundOrder(orderId, order.getVolunteerId(), fromStatus,
                                MallOrderStatus.REJECTED, reason, adminId);
                        notifyReviewResult(snap, false, reason);
                        return snap;
                    });
                });
        // 驳回已提交之后才退现金：先退后驳，退款成功而驳回输给并发的「通过」，就是钱退了、东西照发
        if (rejected != null && isPaidCashOrder(rejected)) {
            refundCash(rejected, reason, adminId);
        }
    }

    /** 这张单是不是「已经付过现金」的：有应付现金、且已离开待支付（下单时没有现金的单永远不是）。 */
    private static boolean isPaidCashOrder(MallOrder o) {
        return o.getPayCashFen() != null && o.getPayCashFen() > 0
                && o.getStatus() != null && o.getStatus() != MallOrderStatus.AWAITING_PAYMENT;
    }

    /**
     * 原路退款。<b>失败不回滚驳回</b>（驳回已提交），把原因记在订单上，后台据此到收付页用交易单 id 重试——
     * 与「通知失败绝不影响业务」同一取舍，代价是它必须被看得见，所以落库而不只打日志。
     */
    private void refundCash(MallOrder order, String reason, Long adminId) {
        Long tradeOrderId = order.getTradeOrderId();
        if (tradeOrderId == null) {
            TradeOrder t = tradeOrderService.findLatestByBiz(TradeFlow.BIZ_MALL_SHIPPING, bizNoOf(order.getId()));
            tradeOrderId = t != null && TradeFlow.isPaidLike(t.getStatus()) ? t.getId() : null;
        }
        String refundNo = null;
        String error = null;
        if (tradeOrderId == null) {
            error = "找不到已支付的交易单，无法自动退款";
        } else {
            TradeDTOs.Refund dto = new TradeDTOs.Refund();
            String why = "兑换单 " + order.getOrderNo() + " 被驳回" + (StringUtils.hasText(reason) ? "：" + reason : "");
            dto.setReason(why.length() > 512 ? why.substring(0, 512) : why);
            try {
                refundNo = tradeRefundService.refund(tradeOrderId, dto, adminId);
            } catch (BusinessException e) {
                error = e.getMessage();
            }
        }
        if (error != null) {
            log.error("[MALL] 兑换单 {} 驳回后现金退款未发起：{}（交易单 {}）", order.getId(), error, tradeOrderId);
        }
        orderMapper.update(null, Wrappers.<MallOrder>lambdaUpdate()
                .eq(MallOrder::getId, order.getId())
                .set(MallOrder::getTradeOrderId, tradeOrderId)
                .set(MallOrder::getCashRefundNo, refundNo)
                .set(MallOrder::getCashRefundError, error == null ? null : cut(error, 255))
                .set(MallOrder::getUpdateTime, LocalDateTime.now()));
    }

    /**
     * 退单的公共部分：CAS 迁移状态 + 还库存 + 还卷 + 退分。
     *
     * @param fromStatus 期望的当前状态（待审核 / 待支付）——写进 WHERE，影响行数为 1 才继续
     * @return 迁移<b>之前</b>读到的订单（调用方据此判断要不要退现金、短信怎么写）
     */
    private MallOrder refundOrder(Long orderId, Long volunteerId, int fromStatus, int targetStatus, String reason,
                                  Long adminId) {
        MallOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getVolunteerId().equals(volunteerId)) {
            throw new BusinessException("兑换单不存在");
        }
        // CAS：只有仍在期望状态的单才能退，影响行数 1 才继续。幂等全靠这一步
        int rows = orderMapper.update(null, Wrappers.<MallOrder>lambdaUpdate()
                .eq(MallOrder::getId, orderId)
                .eq(MallOrder::getStatus, fromStatus)
                .set(MallOrder::getStatus, targetStatus)
                .set(reason != null, MallOrder::getRejectReason, reason)
                .set(adminId != null, MallOrder::getReviewBy, adminId)
                .set(adminId != null, MallOrder::getReviewTime, LocalDateTime.now())
                .set(MallOrder::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("该兑换单当前状态不可取消或驳回");
        }
        restoreStockOrWarn(order.getSpecId(), orderId);
        if (order.getCouponGrantId() != null) {
            couponService.restore(order.getCouponGrantId(), orderId);
        }
        // 实付为 0（兑换卷全额抵扣）时下单就没记流水，退单也不记——账本不收 0 分流水
        if (order.getPoints() != null && order.getPoints() > 0) {
            pointService.record(volunteerId, order.getPoints(), PointSourceType.EXCHANGE, null,
                    PointSourceType.MALL_REFUND_REQUEST_PREFIX + orderId,
                    "兑换退回 " + order.getGoodsName() + "（" + order.getSpecName() + "）",
                    PointSourceType.OPERATOR_SYSTEM, null);
        }
        return order;
    }

    /**
     * 审核通过 → 待领取，<b>同一条语句里生成取货码并快照自提点</b>。
     *
     * <p><b>为什么取货码必须与状态迁移同语句</b>：分成两步的话，中间崩一次就会留下一张
     * 「已通过待领取、但没有取货码」的单——志愿者点开是空白，柜台也无从核销，
     * 而系统里看它一切正常。合成一条 UPDATE 之后这个中间态根本不存在。</p>
     *
     * <p><b>自提点在这一刻快照进三列文本</b>（不是下单时）：审核通过才是「去哪儿领」这件事
     * 被确定下来的时刻。配置只有一个当前值，协会换了办公地点，历史单据仍要还原得出当时的领取点——
     * 与商品名 / 规格名 / 积分三项快照同一条理由。</p>
     *
     * <p>撞 {@code uk_pickup_code} 时换一个码重试（上限 5）。2^50 的空间下这几乎不会发生，
     * 但唯一键是<b>唯一性的来源</b>，重试是它的配套动作——同证书编号那条路。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long orderId, Long adminId) {
        if (orderId == null) {
            throw new BusinessException("兑换单不存在");
        }
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        MallOrder target = orderMapper.selectById(orderId);
        if (target != null && target.getDeliveryType() != null
                && target.getDeliveryType() == MallDeliveryType.EXPRESS) {
            // 快递单没有取货码、也没有自提点：通过即进入「待发货」（领取方式下单后不可改，普通读足够）
            int rows = orderMapper.update(null, Wrappers.<MallOrder>lambdaUpdate()
                    .eq(MallOrder::getId, orderId)
                    .eq(MallOrder::getStatus, MallOrderStatus.PENDING)
                    .set(MallOrder::getStatus, MallOrderStatus.READY)
                    .set(MallOrder::getReviewBy, adminId)
                    .set(MallOrder::getReviewTime, LocalDateTime.now())
                    .set(MallOrder::getUpdateTime, LocalDateTime.now()));
            if (rows != 1) {
                throw new BusinessException("该兑换单当前状态不可审核");
            }
            notifyReviewResult(orderMapper.selectById(orderId), true, null);
            return;
        }
        MallProperties.PickupSite site = mallProperties.getPickupSite();
        for (int attempt = 0; attempt < CODE_RETRY; attempt++) {
            String code = PickupCodeUtil.generate(PickupCodeUtil.DOMAIN_MALL);
            try {
                int rows = orderMapper.update(null, Wrappers.<MallOrder>lambdaUpdate()
                        .eq(MallOrder::getId, orderId)
                        .eq(MallOrder::getStatus, MallOrderStatus.PENDING)
                        .set(MallOrder::getStatus, MallOrderStatus.READY)
                        .set(MallOrder::getPickupCode, code)
                        .set(MallOrder::getPickupSiteName, site.getName())
                        .set(MallOrder::getPickupSiteAddr, site.getAddress())
                        .set(MallOrder::getPickupSitePhone, site.getPhone())
                        .set(MallOrder::getReviewBy, adminId)
                        .set(MallOrder::getReviewTime, LocalDateTime.now())
                        .set(MallOrder::getUpdateTime, LocalDateTime.now()));
                if (rows != 1) {
                    throw new BusinessException("该兑换单当前状态不可审核");
                }
                notifyReviewResult(orderMapper.selectById(orderId), true, null);
                return;
            } catch (DuplicateKeyException e) {
                // 撞的一定是 uk_pickup_code——这条语句只写这一个唯一列
                log.warn("取货码碰撞，换一个重试（第 {} 次）", attempt + 1);
            }
        }
        throw new BusinessException("取货码生成失败，请重试");
    }

    /**
     * 审核结果短信（{@code POINTS_ORDER_REVIEW}）。<b>必须在事务里调</b>——
     * {@code SmsNotifyService} 会挂到 afterCommit 上，事务回滚就什么都不发（短信撤不回）。
     *
     * <p><b>失败绝不影响审核</b>：参数个数错这类编码错误也只记 ERROR（同 {@code EnrollmentService} 的写法），
     * 所以用例必须真的断言短信内容，只断言「审核成功」看不出来。</p>
     */
    private void notifyReviewResult(MallOrder order, boolean approved, String reason) {
        if (order == null) {
            return;
        }
        try {
            String goods = order.getGoodsName() + "（" + order.getSpecName() + "）";
            String remark;
            if (approved && order.getDeliveryType() != null && order.getDeliveryType() == MallDeliveryType.EXPRESS) {
                remark = "我们会尽快寄出，发货后可在小程序「我的兑换」查看快递单号。";
            } else if (approved) {
                String siteName = mallProperties.getPickupSite().getName();
                remark = StringUtils.hasText(siteName)
                        ? "请凭取货码到" + siteName + "领取。"
                        : "请在小程序「我的兑换」查看取货码。";
            } else {
                List<String> returned = new ArrayList<>();
                if (order.getPoints() != null && order.getPoints() > 0) {
                    returned.add("积分");
                }
                if (order.getCouponGrantId() != null) {
                    returned.add("卷");
                }
                if (isPaidCashOrder(order)) {
                    returned.add("所付款项（原路退回）");
                }
                remark = "原因：" + (reason == null ? "" : reason) + "。"
                        + (returned.isEmpty() ? "" : "所用" + String.join("与", returned) + "已退回。");
            }
            smsNotifyService.notifyVolunteer(order.getVolunteerId(), SmsNotifyTemplate.POINTS_ORDER_REVIEW,
                    SmsNotifyTemplate.POINTS_ORDER_REVIEW.params(goods, approved ? "通过" : "未通过", remark));
        } catch (Exception ex) {
            log.error("[SMS-NOTIFY] 兑换审核通知失败 orderId={}", order.getId(), ex);
        }
    }

    /**
     * 管理员现场核销：<b>按取货码，不按订单 id</b>。
     *
     * <p>扫码枪扫出来的是码；做成「先按码查 id、再按 id 核销」会多一次往返，
     * 还把一个本可原子的动作拆成两步。</p>
     *
     * @return 核销掉的那张单，供柜台核对该发什么
     */
    @Transactional(rollbackFor = Exception.class)
    public MallOrderVO verify(String rawCode, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        return doVerify(rawCode, adminId, PickupOperatorType.ADMIN, null);
    }

    /**
     * 核销员（志愿者）在小程序扫码核销（Row 8 F「企业核销员可以在前端用扫一扫功能给申请兑换的志愿者核销商品」）。
     *
     * <p><b>资格在接口里兜底</b>：小程序据 {@code /v/donate/verifiers/me} 决定显不显示入口只是 UX，
     * 这里再用 {@link MallVerifierService#findActive} 判一次——停用 / 注销的核销员即时失效。</p>
     *
     * <p><b>不能核销自己的单</b>：核销员给自己核销等于自己确认「东西已交到我手里」，这一步的全部意义
     * 就在于由另一个人确认，所以挡掉。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public MallOrderVO verifyByVerifier(String rawCode, Long volunteerId) {
        if (volunteerId == null) {
            throw new BusinessException("操作人不能为空");
        }
        MallVerifier verifier = verifierService.findActive(volunteerId);
        if (verifier == null) {
            throw new BusinessException("您不是核销员，无法核销");
        }
        return doVerify(rawCode, volunteerId, PickupOperatorType.VERIFIER, verifier);
    }

    /**
     * 核销的公共部分。<b>一次性由 CAS 保证</b>（{@code status = 待领取} 写进 WHERE）：同一个码连扫两次、
     * 两个窗口同时扫，都只会有一次成功。
     *
     * <p>失败时要说得准：码不存在 / 已核销过（带时间）/ 单子状态不对，是三件不同的事。
     * 一律报「核销失败」会让柜台前的人不知道该不该把东西给出去。</p>
     */
    private MallOrderVO doVerify(String rawCode, Long operatorId, int operatorType, MallVerifier verifier) {
        String code = PickupCodeUtil.normalize(rawCode);
        // 【格式不对】与【查无此单】分开报，剩下的才合并——分寸在这里：
        // 字母表与码长是**公开信息**（码印在志愿者自己的条码上），说「13 位、不含 0 1 I O」
        // 不泄露任何东西，却能让柜台前的人知道是抄错了一位、而不是白跑一趟。
        // 合并它们反而制造了 javadoc 里要避免的那种处境：分不清「抄错了」和「没这单」。
        if (!PickupCodeUtil.isValid(code)) {
            throw new BusinessException("取货码格式不正确：应为 PU 开头的 13 位，且不含 0、1、I、O，请核对");
        }
        if (!PickupCodeUtil.isValid(code, PickupCodeUtil.DOMAIN_MALL)) {
            // 形态合法但不属于商城域（如纸质证书的码）——与「查无此单」合并成同一句。
            // 这一类必须合：分开报等于告诉持码人「你这码是真的，只是走错了窗口」，
            // 而他本就不该从商城柜台得到关于另一个域的任何信息。
            throw new BusinessException("取货码无效");
        }
        if (verifier != null) {
            MallOrder target = orderMapper.selectOne(Wrappers.<MallOrder>lambdaQuery()
                    .eq(MallOrder::getPickupCode, code));
            if (target != null) {
                if (target.getVolunteerId().equals(operatorId)) {
                    throw new BusinessException("不能核销自己的兑换单");
                }
                if (verifier.getEnterpriseId() != null) {
                    // V4 企业核销员：只能核销本企业赞助的商品。不属于本企业的码与「查无此单」同一句（理由同上）
                    MallGoods goods = goodsMapper.selectById(target.getGoodsId());
                    if (goods == null || !verifier.getEnterpriseId().equals(goods.getSponsorEnterpriseId())) {
                        throw new BusinessException("取货码无效");
                    }
                }
            }
        }
        int rows = orderMapper.update(null, Wrappers.<MallOrder>lambdaUpdate()
                .eq(MallOrder::getPickupCode, code)
                .eq(MallOrder::getStatus, MallOrderStatus.READY)
                .set(MallOrder::getStatus, MallOrderStatus.PICKED)
                .set(MallOrder::getPickupTime, LocalDateTime.now())
                .set(MallOrder::getPickupOperator, operatorId)
                .set(MallOrder::getPickupOperatorType, operatorType)
                .set(MallOrder::getUpdateTime, LocalDateTime.now()));
        // 复核必须是当前读：核销员路径在进入这里之前已做过普通读（查资格、预查目标单），读视图定在那一刻，
        // 普通读看不见并发赢家刚提交的「已领取」，会把「已核销过」报成「当前不可领取（待领取）」
        MallOrder order = orderMapper.selectByPickupCodeForShare(code);
        if (rows != 1) {
            if (order == null) {
                throw new BusinessException("取货码无效");
            }
            if (order.getStatus() != null && order.getStatus() == MallOrderStatus.PICKED) {
                throw new BusinessException("该取货码已于 " + order.getPickupTime() + " 核销过");
            }
            throw new BusinessException("该兑换单当前不可领取（"
                    + MallOrderStatus.labelOf(order.getStatus()) + "）");
        }
        MallOrderVO vo = toVO(order, true, false);
        vo.setVolunteerName(volunteerQueryService.listNamesByIds(List.of(order.getVolunteerId()))
                .get(order.getVolunteerId()));
        return vo;
    }

    // ---------------- 现金付款（商城快递批） ----------------

    /**
     * 发起付款：换 openid → 让 trade 下单（复用活单）→ 返回唤起支付的参数。
     *
     * <p><b>持志愿者锁</b>（与取消同一把）：否则「取消刚把旧交易单关掉、这里又建了一张新的」，
     * 用户付进那张新单时兑换单已经取消了。付款截止同时传给 trade，交易单不会比兑换单活得久。</p>
     *
     * <p>code 换 openid 是网络调用，放在锁外先换好：不值得为它占着锁。</p>
     */
    public TradeVOs.Prepay pay(Long orderId, Long volunteerId, String code) {
        if (orderId == null || volunteerId == null) {
            throw new BusinessException("参数不完整");
        }
        MallOrder peek = requireOwnAwaiting(orderId, volunteerId);
        String openid = identityService.openidOf(code);
        return DistributedLockSupport.runLocked(redissonClient, PointService.LOCK_KEY_PREFIX + volunteerId, () -> {
            MallOrder order = requireOwnAwaiting(orderId, volunteerId);
            TradeDTOs.CreateOrder dto = new TradeDTOs.CreateOrder();
            dto.setBizType(TradeFlow.BIZ_MALL_SHIPPING);
            dto.setBizNo(bizNoOf(orderId));
            dto.setVolunteerId(volunteerId);
            dto.setPayerOpenid(openid);
            dto.setSubject(cut("积分商城·" + order.getGoodsName() + "（" + order.getSpecName() + "）", 128));
            dto.setAmountFen(order.getPayCashFen());
            dto.setExpireAt(order.getPayExpireTime());
            dto.setRemark("兑换单 " + order.getOrderNo());
            return tradeOrderService.createOrder(dto);
        });
    }

    private MallOrder requireOwnAwaiting(Long orderId, Long volunteerId) {
        MallOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getVolunteerId().equals(volunteerId)) {
            throw new BusinessException("兑换单不存在");
        }
        if (order.getStatus() == null || order.getStatus() != MallOrderStatus.AWAITING_PAYMENT) {
            throw new BusinessException("这张兑换单不需要付款（当前："
                    + MallOrderStatus.labelOf(order.getStatus(), order.getDeliveryType()) + "）");
        }
        if (order.getPayExpireTime() != null && !LocalDateTime.now().isBefore(order.getPayExpireTime())) {
            throw new BusinessException("已超过付款时限，这张兑换单会自动取消");
        }
        return order;
    }

    /**
     * 付款成功回写：待支付 → 待审核。<b>幂等</b>：事件与补偿任务可能同时到，CAS 只让一个成功。
     *
     * <p>在<b>独立事务</b>里执行：它从交易提交之后的事件回调里被调，那时外层事务已经提交，加入它等于写了不提交。</p>
     *
     * <p>CAS 失败而单子已是已取消 / 已驳回：取消与驳回都先关交易单、关不掉就放弃，所以正常路径到不了这里；
     * 真到了就是钱收了、单子没了，<b>必须喊出来</b>（ERROR），由对账与人工退款兜底。</p>
     *
     * @return 本次是否真的完成了迁移
     */
    public boolean onPaid(String bizNo, Long tradeOrderId) {
        Long orderId = parseBizNo(bizNo);
        if (orderId == null) {
            log.error("[MALL] 收到无法识别的商城付款 bizNo={} tradeOrderId={}", bizNo, tradeOrderId);
            return false;
        }
        Boolean moved = requiresNew.execute(s -> {
            LocalDateTime now = LocalDateTime.now();
            int rows = orderMapper.update(null, Wrappers.<MallOrder>lambdaUpdate()
                    .eq(MallOrder::getId, orderId)
                    .eq(MallOrder::getStatus, MallOrderStatus.AWAITING_PAYMENT)
                    .set(MallOrder::getStatus, MallOrderStatus.PENDING)
                    .set(MallOrder::getTradeOrderId, tradeOrderId)
                    .set(MallOrder::getPaidTime, now)
                    .set(MallOrder::getUpdateTime, now));
            if (rows == 1) {
                return true;
            }
            MallOrder order = orderMapper.selectById(orderId);
            if (order == null) {
                log.error("[MALL] 付款指向不存在的兑换单 orderId={} tradeOrderId={}", orderId, tradeOrderId);
            } else if (MallOrderStatus.isRefunded(order.getStatus())) {
                log.error("[MALL] 已{}的兑换单收到付款，需人工退款 orderId={} tradeOrderId={}",
                        MallOrderStatus.labelOf(order.getStatus()), orderId, tradeOrderId);
            }
            return false;
        });
        return Boolean.TRUE.equals(moved);
    }

    /**
     * 补偿任务：处理所有「待支付」的单。事件丢了的，照样推进；超过付款截止的，取消并全部归还。
     *
     * <p>判断顺序是承重的：<b>先问 trade 付了没有，再决定取消</b>。交易单还在待支付但已过期的，
     * 先让 trade 主动查一次单（以渠道为准）——渠道说没付、它才会被关掉，这边才会取消。</p>
     *
     * @return 本轮处理（推进或取消）的单数
     */
    public int syncAwaitingPayments() {
        List<MallOrder> awaiting = orderMapper.selectList(Wrappers.<MallOrder>lambdaQuery()
                .eq(MallOrder::getStatus, MallOrderStatus.AWAITING_PAYMENT)
                .orderByAsc(MallOrder::getPayExpireTime)
                .last("LIMIT 200"));
        int handled = 0;
        for (MallOrder order : awaiting) {
            try {
                if (syncOne(order)) {
                    handled++;
                }
            } catch (RuntimeException e) {
                log.error("[MALL] 待支付兑换单 {} 同步失败，下一轮再试", order.getId(), e);
            }
        }
        return handled;
    }

    private boolean syncOne(MallOrder order) {
        String bizNo = bizNoOf(order.getId());
        TradeOrder trade = tradeOrderService.findLatestByBiz(TradeFlow.BIZ_MALL_SHIPPING, bizNo);
        LocalDateTime now = LocalDateTime.now();
        if (trade != null && trade.getStatus() != null && trade.getStatus() == TradeFlow.ORDER_PENDING
                && trade.getExpireTime() != null && !now.isBefore(trade.getExpireTime())) {
            // 过期还挂着：以渠道为准查一次（付了就推成已支付，没付就关掉）
            tradeOrderService.queryAndSync(trade.getOutTradeNo(), TradeFlow.SOURCE_SCAN);
            trade = tradeOrderService.findLatestByBiz(TradeFlow.BIZ_MALL_SHIPPING, bizNo);
        }
        if (trade != null && TradeFlow.isPaidLike(trade.getStatus())) {
            return onPaid(bizNo, trade.getId());
        }
        if (order.getPayExpireTime() == null || now.isBefore(order.getPayExpireTime())) {
            return false;
        }
        // 超时：与用户取消走同一条路（同一把锁、先关交易单、关不掉就不取消）
        return Boolean.TRUE.equals(DistributedLockSupport.runLocked(redissonClient,
                PointService.LOCK_KEY_PREFIX + order.getVolunteerId(), () -> {
                    if (!tradeOrderService.closeByBiz(TradeFlow.BIZ_MALL_SHIPPING, bizNo)) {
                        TradeOrder paid = tradeOrderService.findLatestByBiz(TradeFlow.BIZ_MALL_SHIPPING, bizNo);
                        return paid != null && onPaid(bizNo, paid.getId());
                    }
                    return transactionTemplate.execute(s -> {
                        MallOrder current = orderMapper.selectById(order.getId());
                        if (current == null || current.getStatus() == null
                                || current.getStatus() != MallOrderStatus.AWAITING_PAYMENT) {
                            return false;
                        }
                        refundOrder(order.getId(), order.getVolunteerId(), MallOrderStatus.AWAITING_PAYMENT,
                                MallOrderStatus.CANCELLED, "超过付款时限未付款，已自动取消", null);
                        return true;
                    });
                }));
    }

    // ---------------- 快递发货与收货（商城快递批） ----------------

    /** 后台登记发货：只对「快递 + 待发货」的单，CAS 一次性。 */
    @Transactional(rollbackFor = Exception.class)
    public void ship(Long orderId, ExpressDTO dto, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        if (dto == null) {
            throw new BusinessException("请填写快递信息");
        }
        ExpressCompany company = DonateShipmentService.requireCompany(dto.getExpressCode());
        String no = DonateShipmentService.normalizeExpressNo(dto.getExpressNo());
        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.update(null, Wrappers.<MallOrder>lambdaUpdate()
                .eq(MallOrder::getId, orderId)
                .eq(MallOrder::getDeliveryType, MallDeliveryType.EXPRESS)
                .eq(MallOrder::getStatus, MallOrderStatus.READY)
                .set(MallOrder::getStatus, MallOrderStatus.SHIPPED)
                .set(MallOrder::getExpressCode, company.getCode())
                .set(MallOrder::getExpressCompany, company.getLabel())
                .set(MallOrder::getExpressNo, no)
                .set(MallOrder::getShipTime, now)
                .set(MallOrder::getShipBy, adminId)
                .set(MallOrder::getUpdateTime, now));
        if (rows != 1) {
            MallOrder o = orderId == null ? null : orderMapper.selectById(orderId);
            if (o == null) {
                throw new BusinessException("兑换单不存在");
            }
            if (o.getDeliveryType() == null || o.getDeliveryType() != MallDeliveryType.EXPRESS) {
                throw new BusinessException("自提的兑换单不需要发货");
            }
            throw new BusinessException("该兑换单当前不能发货（"
                    + MallOrderStatus.labelOf(o.getStatus(), o.getDeliveryType()) + "）");
        }
    }

    /** 收件人确认收货：已发货 → 已签收。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceipt(Long orderId, Long volunteerId) {
        if (orderId == null || volunteerId == null) {
            throw new BusinessException("参数不完整");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = orderMapper.update(null, Wrappers.<MallOrder>lambdaUpdate()
                .eq(MallOrder::getId, orderId)
                .eq(MallOrder::getVolunteerId, volunteerId)
                .eq(MallOrder::getStatus, MallOrderStatus.SHIPPED)
                .set(MallOrder::getStatus, MallOrderStatus.PICKED)
                .set(MallOrder::getPickupTime, now)
                .set(MallOrder::getPickupOperator, volunteerId)
                .set(MallOrder::getPickupOperatorType, PickupOperatorType.RECIPIENT)
                .set(MallOrder::getUpdateTime, now));
        if (rows != 1) {
            MallOrder o = orderMapper.selectById(orderId);
            if (o == null || !o.getVolunteerId().equals(volunteerId)) {
                throw new BusinessException("兑换单不存在");
            }
            throw new BusinessException("该兑换单当前不能确认收货（"
                    + MallOrderStatus.labelOf(o.getStatus(), o.getDeliveryType()) + "）");
        }
    }

    /**
     * 发货满 N 天仍未确认的，系统自动确认收货。<b>一条 UPDATE、按状态与发货时间筛</b>，
     * 走 {@code idx_status_ship_time}；每轮上限 500，余下的下一轮继续。
     *
     * @return 本轮自动确认的单数
     */
    public int autoReceive() {
        LocalDateTime now = LocalDateTime.now();
        int days = Math.max(1, mallProperties.getExpress().getAutoReceiveDays());
        return orderMapper.update(null, Wrappers.<MallOrder>lambdaUpdate()
                .eq(MallOrder::getStatus, MallOrderStatus.SHIPPED)
                .lt(MallOrder::getShipTime, now.minusDays(days))
                .set(MallOrder::getStatus, MallOrderStatus.PICKED)
                .set(MallOrder::getPickupTime, now)
                .set(MallOrder::getPickupOperatorType, PickupOperatorType.SYSTEM)
                .set(MallOrder::getUpdateTime, now)
                .last("LIMIT 500"));
    }

    /** 兑换单在 trade 里的业务单据号。 */
    static String bizNoOf(Long orderId) {
        return String.valueOf(orderId);
    }

    private static Long parseBizNo(String bizNo) {
        try {
            return bizNo == null ? null : Long.valueOf(bizNo.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    // ---------------- 查询 ----------------

    /** 我的兑换（Row 8 C），可按状态筛选。 */
    public PageResult<MallOrderVO> listMine(Long volunteerId, PageQuery query, Integer status) {
        IPage<MallOrder> page = orderMapper.selectPage(query.toPage(),
                Wrappers.<MallOrder>lambdaQuery()
                        .eq(MallOrder::getVolunteerId, volunteerId)
                        .eq(status != null, MallOrder::getStatus, status)
                        .orderByDesc(MallOrder::getId));
        PageResult<MallOrderVO> result = PageResult.of(page.convert(o -> toVO(o, false, false)));
        markReviewed(result.getRecords());
        return result;
    }

    /**
     * 我的兑换详情，<b>带取货码条码图</b>。
     *
     * <p>归属校验与「不存在」返回同一句话，防按 id 枚举别人的单——同证书下载那条口径。</p>
     */
    public MallOrderVO detailMine(Long orderId, Long volunteerId) {
        MallOrder order = orderId == null ? null : orderMapper.selectById(orderId);
        if (order == null || !order.getVolunteerId().equals(volunteerId)) {
            throw new BusinessException("兑换单不存在");
        }
        MallOrderVO vo = toVO(order, false, true);
        markReviewed(List.of(vo));
        return vo;
    }

    /**
     * 后台兑换单列表。keyword 同时试三处：订单号、商品名、兑换人（姓名模糊 / 手机号精确）。
     *
     * <p>⚠️ 兑换人那一路经 {@code VolunteerQueryService.findIdsByNameOrPhone} 换 id，
     * <b>有条数上限且是静默截断的</b>——关键词太短时应提示用户填得更完整。
     * donate 不直连 volunteer 表：手机号是密文，姓名 LIKE 也该留在 auth 一处。</p>
     */
    public PageResult<MallOrderVO> listForAdmin(PageQuery query, Integer status, String keyword) {
        List<Long> volunteerIds = (keyword == null || keyword.isBlank())
                ? List.of() : volunteerQueryService.findIdsByNameOrPhone(keyword, 200);
        IPage<MallOrder> page = orderMapper.selectPage(query.toPage(),
                Wrappers.<MallOrder>lambdaQuery()
                        .eq(status != null, MallOrder::getStatus, status)
                        .and(keyword != null && !keyword.isBlank(), w -> {
                            w.like(MallOrder::getOrderNo, keyword)
                                    .or().like(MallOrder::getGoodsName, keyword);
                            if (!volunteerIds.isEmpty()) {
                                w.or().in(MallOrder::getVolunteerId, volunteerIds);
                            }
                        })
                        .orderByDesc(MallOrder::getId));
        PageResult<MallOrderVO> result = PageResult.of(page.convert(o -> toVO(o, true, false)));
        fillNames(result.getRecords());
        markReviewed(result.getRecords());
        return result;
    }

    /**
     * 赞助企业看自己商品的兑换单（V4 爱心企业批）：只给 {@link SponsorOrderVO} 那几项——没有取货码、没有收件信息、兑换人只留姓。
     * 企业 id 是 Long（来自登录态），拼进子查询不是注入面。
     */
    public PageResult<SponsorOrderVO> listForSponsor(Long enterpriseId, PageQuery query, Integer status) {
        if (enterpriseId == null) {
            throw new BusinessException("企业不能为空");
        }
        IPage<MallOrder> page = orderMapper.selectPage(query.toPage(), Wrappers.<MallOrder>lambdaQuery()
                .eq(status != null, MallOrder::getStatus, status)
                .inSql(MallOrder::getGoodsId, "SELECT id FROM mall_goods WHERE sponsor_enterprise_id = " + enterpriseId.longValue())
                .orderByDesc(MallOrder::getId));
        Map<Long, String> names = volunteerQueryService.listNamesByIds(
                page.getRecords().stream().map(MallOrder::getVolunteerId).collect(Collectors.toSet()));
        return PageResult.of(page.convert(o -> {
            SponsorOrderVO vo = new SponsorOrderVO();
            vo.setId(o.getId());
            vo.setOrderNo(o.getOrderNo());
            vo.setGoodsId(o.getGoodsId());
            vo.setGoodsName(o.getGoodsName());
            vo.setSpecName(o.getSpecName());
            vo.setGoodsPoints(nz(o.getPoints()) - nz(o.getShippingPoints()));
            vo.setDeliveryType(o.getDeliveryType());
            vo.setDeliveryTypeLabel(MallDeliveryType.labelOf(o.getDeliveryType()));
            vo.setStatus(o.getStatus());
            vo.setStatusLabel(MallOrderStatus.labelOf(o.getStatus(), o.getDeliveryType()));
            vo.setVolunteerMaskedName(com.hengde.common.utils.MaskUtil.maskName(names.get(o.getVolunteerId())));
            vo.setCreateTime(o.getCreateTime());
            vo.setPickupTime(o.getPickupTime());
            return vo;
        }));
    }

    /**
     * 赞助商品的已领取兑换单（企业积分账本补记用，按 id 翻页）。
     */
    public List<com.hengde.donate.vo.SponsorPickedOrderView> pickedSponsorOrders(LocalDateTime since, long afterId, int limit) {
        return orderMapper.selectPickedSponsorOrders(MallOrderStatus.PICKED, since, afterId, Math.max(1, Math.min(limit, 1000)));
    }

    /** 这张单是不是「他自己的、已领取的、赞助商品」的兑换单；不是返回 null（赞助商评价的资格判定）。 */
    public com.hengde.donate.vo.SponsorPickedOrderView findPickedSponsorOrder(Long orderId, Long volunteerId) {
        if (orderId == null || volunteerId == null) {
            return null;
        }
        return orderMapper.selectPickedSponsorOrder(orderId, volunteerId, MallOrderStatus.PICKED);
    }

    /**
     * 「全部兑换记录」——Row 8 C 明写要展示<b>全部人的</b>兑换记录。
     *
     * <p>只放「谁 · 兑换了什么 · 什么时候」，<b>不含订单编号与取货码</b>：
     * 取货码是柜台上的持有者凭据，在人人可见的列表里出现一次，就等于把东西送给任何看见的人。
     * 「公开到什么程度」本身待协会确认。</p>
     *
     * <p>只列已通过审核之后的单（待领取 / 已领取）——待审核与已驳回是过程态，
     * 公开出去会让人以为「他兑到了」。</p>
     */
    public PageResult<ExchangeRecordVO> listExchangeRecords(PageQuery query) {
        IPage<MallOrder> page = orderMapper.selectPage(query.toPage(),
                Wrappers.<MallOrder>lambdaQuery()
                        .in(MallOrder::getStatus, MallOrderStatus.READY, MallOrderStatus.SHIPPED,
                                MallOrderStatus.PICKED)
                        .orderByDesc(MallOrder::getId));
        Map<Long, String> names = volunteerQueryService.listNamesByIds(
                page.getRecords().stream().map(MallOrder::getVolunteerId).collect(Collectors.toSet()));
        return PageResult.of(page.convert(o -> {
            ExchangeRecordVO vo = new ExchangeRecordVO();
            vo.setVolunteerName(names.get(o.getVolunteerId()));
            vo.setGoodsName(o.getGoodsName());
            vo.setSpecName(o.getSpecName());
            vo.setCreateTime(o.getCreateTime());
            return vo;
        }));
    }

    /** 一次查出这批单里哪些已评价，避免逐条查（Row 8 C「我的兑换」要显示评价状态）。 */
    private void markReviewed(List<MallOrderVO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> ids = records.stream().map(MallOrderVO::getId).toList();
        Set<Long> reviewed = reviewMapper.selectList(Wrappers.<MallGoodsReview>lambdaQuery()
                        .select(MallGoodsReview::getOrderId)
                        .in(MallGoodsReview::getOrderId, ids))
                .stream().map(MallGoodsReview::getOrderId).collect(Collectors.toCollection(HashSet::new));
        records.forEach(vo -> vo.setReviewed(reviewed.contains(vo.getId())));
    }

    private void fillNames(List<MallOrderVO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Map<Long, String> names = volunteerQueryService.listNamesByIds(
                records.stream().map(MallOrderVO::getVolunteerId).collect(Collectors.toSet()));
        records.forEach(vo -> vo.setVolunteerName(names.get(vo.getVolunteerId())));
    }

    /**
     * @param forAdmin  管理端才带兑换人 id 与核销人类型
     * @param withCode  详情才给条码图（列表页给一堆 base64 图会把响应撑爆）
     */
    private MallOrderVO toVO(MallOrder o, boolean forAdmin, boolean withCode) {
        MallOrderVO vo = new MallOrderVO();
        vo.setId(o.getId());
        vo.setOrderNo(o.getOrderNo());
        vo.setGoodsId(o.getGoodsId());
        vo.setGoodsName(o.getGoodsName());
        vo.setSpecName(o.getSpecName());
        vo.setPoints(o.getPoints());
        // V49 已回填存量行；这里再兜一次 null，免得一张漏回填的单在界面上显示「标价 null」
        vo.setOriginalPoints(o.getOriginalPoints() != null ? o.getOriginalPoints() : o.getPoints());
        vo.setCouponName(o.getCouponName());
        vo.setCouponDeductPoints(o.getCouponDeductPoints());
        vo.setStatus(o.getStatus());
        vo.setStatusLabel(MallOrderStatus.labelOf(o.getStatus(), o.getDeliveryType()));
        vo.setDeliveryType(o.getDeliveryType());
        vo.setDeliveryTypeLabel(MallDeliveryType.labelOf(o.getDeliveryType()));
        vo.setGoodsCashFen(nz(o.getGoodsCashFen()));
        vo.setShippingFeeFen(nz(o.getShippingFeeFen()));
        vo.setShippingPayType(o.getShippingPayType());
        vo.setShippingPayTypeLabel(MallShippingPayType.labelOf(o.getShippingPayType()));
        vo.setShippingPoints(nz(o.getShippingPoints()));
        vo.setPointsPerYuan(o.getPointsPerYuan());
        vo.setPayCashFen(nz(o.getPayCashFen()));
        vo.setPayCashYuan(TradeFlow.yuan(nz(o.getPayCashFen())));
        vo.setPayExpireTime(o.getPayExpireTime());
        vo.setPaidTime(o.getPaidTime());
        vo.setRecvName(o.getRecvName());
        vo.setRecvPhone(o.getRecvPhone() == null ? null : cryptoUtil.decrypt(o.getRecvPhone()));
        vo.setRecvAddress(o.getRecvAddress());
        vo.setExpressCompany(o.getExpressCompany());
        vo.setExpressNo(o.getExpressNo());
        vo.setShipTime(o.getShipTime());
        vo.setPickupSiteName(o.getPickupSiteName());
        vo.setPickupSiteAddr(o.getPickupSiteAddr());
        vo.setPickupSitePhone(o.getPickupSitePhone());
        vo.setPickupTime(o.getPickupTime());
        vo.setRejectReason(o.getRejectReason());
        vo.setCreateTime(o.getCreateTime());
        if (forAdmin) {
            vo.setVolunteerId(o.getVolunteerId());
            vo.setPickupOperatorType(o.getPickupOperatorType());
            vo.setTradeOrderId(o.getTradeOrderId());
            vo.setCashRefundNo(o.getCashRefundNo());
            vo.setCashRefundError(o.getCashRefundError());
        }
        // 取货码只在「待领取」时下发：已领取的再给出去没有用途，只是多一次泄露面
        boolean ready = o.getStatus() != null && o.getStatus() == MallOrderStatus.READY;
        if (ready) {
            vo.setPickupCode(o.getPickupCode());
            if (withCode && o.getPickupCode() != null) {
                vo.setPickupBarcode(PickupCodeUtil.toBarcodeDataUrl(o.getPickupCode()));
            }
        }
        return vo;
    }

    /**
     * 还库存，并把「没还成」这件事记下来。<b>不抛异常</b>。
     *
     * <p>0 行只有两种可能，性质完全不同：</p>
     * <ul>
     *   <li><b>规格已软删</b>——正常运营。软删后 {@code deductStock} 再也扣不到它，
     *       库存数字已无消费方，不还是对的；记一行 info 只为让日志能对上账。</li>
     *   <li><b>规格根本不存在</b>——数据完整性问题。本表无外键，订单可以挂着一个不存在的
     *       specId 落库而无人拦阻。<b>这一种必须响亮</b>，否则永远没人知道。</li>
     * </ul>
     *
     * <p><b>为什么不抛</b>：抛出会连同退分一起回滚，志愿者就会因为管理员删过一个规格而
     * 永远取消不了这张单——比少还一件库存严重得多。这与
     * {@code SmsNotifyService}「通知失败绝不影响业务」是同一条取舍：
     * <b>代价是它在线上只留一行日志，所以那行日志必须说得清是哪一种。</b></p>
     */
    private void restoreStockOrWarn(Long specId, Long orderId) {
        if (specMapper.restoreStock(specId) == 1) {
            return;
        }
        Integer deleted = specMapper.selectDeletedFlag(specId);
        if (deleted == null) {
            log.warn("还库存失败：兑换单 {} 的规格 {} 在库中不存在，订单已退分但库存无处归还，请核对数据",
                    orderId, specId);
        } else {
            log.info("规格 {} 已软删，兑换单 {} 不归还库存（软删后该库存已无消费方）", specId, orderId);
        }
    }

    /** 单号：时间戳 + 6 位随机。与证书编号同形态。 */
    private String nextOrderNo() {
        return "DH" + LocalDateTime.now().format(NO_FMT)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
