package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.service.PointService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.pickup.PickupCodeUtil;
import com.hengde.donate.config.MallProperties;
import com.hengde.donate.constant.MallDeliveryType;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.constant.MallOrderStatus;
import com.hengde.donate.dao.MallGoodsMapper;
import com.hengde.donate.dao.MallGoodsSpecMapper;
import com.hengde.donate.dao.MallOrderMapper;
import com.hengde.donate.entity.MallGoods;
import com.hengde.donate.dao.MallGoodsReviewMapper;
import com.hengde.donate.entity.MallGoodsReview;
import com.hengde.donate.entity.MallGoodsSpec;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.vo.ExchangeRecordVO;
import com.hengde.donate.vo.MallOrderVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
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
 * 否则同一笔积分能下十张待审单。<b>这条差异是有意的，日后不要为了「统一」而改掉。</b></p>
 *
 * <p><b>一单一件（数量恒为 1）</b>是本批的显式取舍，不是漏做——理由与影响见
 * {@code 文档/v3/V3规划.md} 的「商城批」一节。要加数量时，库存 CAS 的
 * {@code stock - 1}/{@code stock + 1}、订单表、D8 的快照、退分金额<b>四处必须同时改</b>，
 * 而只有 SQL 里那个 {@code 1} 会在改的时候撞到眼前。</p>
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

    /**
     * 下单兑换。
     *
     * <p><b>锁在事务之外获取、包住整个事务</b>（项目既有约定），且用的是
     * {@link PointService#LOCK_KEY_PREFIX} <b>同一把锁</b>——手工扣分与兑换扣分必须互斥，
     * 两者都要「先看余额、再扣」，各锁各的会让余额检查同时通过、把余额扣成负数。</p>
     *
     * <p><b>顺序是承重的：先扣库存，再读快照，最后扣分。</b></p>
     * <ol>
     *   <li><b>扣库存的 CAS 同时校验了商品可下单</b>（上架 / 未隐藏 / 未删 / 规格未删 / 有货），
     *       一条语句解决，不产生「先读商品再改规格」那个锁升级序列；</li>
     *   <li><b>读快照放在扣减之后</b>：此时本事务已持有该规格行的排他锁，读到的就是当前值，
     *       不会把一个刚被改过的旧价格快照进订单；</li>
     *   <li>扣分放最后，与订单同事务——积分与业务变更同成同败。</li>
     * </ol>
     *
     * @param volunteerId 志愿者 id
     * @param specId      规格 id
     * @return 落库后的订单
     */
    public MallOrder placeOrder(Long volunteerId, Long specId) {
        if (volunteerId == null) {
            throw new BusinessException("志愿者不能为空");
        }
        if (specId == null) {
            throw new BusinessException("请选择商品规格");
        }
        return DistributedLockSupport.runLocked(redissonClient,
                PointService.LOCK_KEY_PREFIX + volunteerId,
                () -> transactionTemplate.execute(status -> doPlaceOrder(volunteerId, specId)));
    }

    private MallOrder doPlaceOrder(Long volunteerId, Long specId) {
        // ① 扣库存 + 校验商品可下单，一条 CAS
        if (specMapper.deductStock(specId, MallGoodsStatus.ON_SALE) != 1) {
            throw new BusinessException(explainDeductFailure(specId));
        }
        // ② 扣减成功后再读，拿到的是当前值（本事务已持有该行排他锁）
        MallGoodsSpec spec = specMapper.selectById(specId);
        MallGoods goods = goodsMapper.selectById(spec.getGoodsId());
        int points = spec.getPoints();
        if (points <= 0) {
            throw new BusinessException("该规格未设置所需积分");
        }
        // ③ 余额检查与手工扣分共用同一处实现；本方法已在同一把锁内
        pointService.assertDeductible(volunteerId, -points);

        MallOrder order = new MallOrder();
        order.setOrderNo(nextOrderNo());
        order.setVolunteerId(volunteerId);
        order.setGoodsId(spec.getGoodsId());
        order.setSpecId(specId);
        // 快照三项：规格可改可删，事后要还原得出「当时买的是什么、花了多少分」
        order.setGoodsName(goods == null ? "" : goods.getName());
        order.setSpecName(spec.getName());
        order.setPoints(points);
        order.setStatus(MallOrderStatus.PENDING);
        order.setDeliveryType(MallDeliveryType.PICKUP);
        orderMapper.insert(order);

        // ④ 扣分与订单同事务；source_id = 订单 id，uk_source 保幂等
        pointService.record(volunteerId, -points, PointSourceType.EXCHANGE, order.getId(),
                "兑换 " + order.getGoodsName() + "（" + order.getSpecName() + "）",
                PointSourceType.OPERATOR_SYSTEM, null);
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
        return "该规格库存不足";
    }

    /**
     * 取消兑换：退分 + 还库存。
     *
     * <p><b>幂等靠订单状态的 CAS</b>：只有把「待审核」原子地改成「已取消」成功的那一次
     * 才会往下走，双击取消只会退一次。<b>退分与还库存必须同事务</b>——
     * 只还库存不退分、或反过来，都是账实分离。</p>
     *
     * <p>退分复用 {@code EXCHANGE} 来源码记一笔<b>正数</b>，靠
     * {@code sys:mall-refund:} 前缀的 requestId 保幂等——理由是<b>口径</b>：
     * 换一个非消费类来源码会让「已使用积分」与「累计获得」同时算错。</p>
     */
    public void cancel(Long orderId, Long volunteerId) {
        if (orderId == null || volunteerId == null) {
            throw new BusinessException("参数不完整");
        }
        DistributedLockSupport.runLocked(redissonClient,
                PointService.LOCK_KEY_PREFIX + volunteerId,
                () -> transactionTemplate.execute(s -> {
                    refundOrder(orderId, volunteerId, MallOrderStatus.CANCELLED, null, null);
                    return null;
                }));
    }

    /** 后台驳回：退分 + 还库存，记原因与审核人。 */
    public void reject(Long orderId, String reason, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        MallOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("兑换单不存在");
        }
        DistributedLockSupport.runLocked(redissonClient,
                PointService.LOCK_KEY_PREFIX + order.getVolunteerId(),
                () -> transactionTemplate.execute(s -> {
                    refundOrder(orderId, order.getVolunteerId(), MallOrderStatus.REJECTED, reason, adminId);
                    return null;
                }));
    }

    private void refundOrder(Long orderId, Long volunteerId, int targetStatus, String reason, Long adminId) {
        MallOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getVolunteerId().equals(volunteerId)) {
            throw new BusinessException("兑换单不存在");
        }
        // CAS：只有仍在待审核的单才能退，影响行数 1 才继续。幂等全靠这一步
        int rows = orderMapper.update(null, Wrappers.<MallOrder>lambdaUpdate()
                .eq(MallOrder::getId, orderId)
                .eq(MallOrder::getStatus, MallOrderStatus.PENDING)
                .set(MallOrder::getStatus, targetStatus)
                .set(reason != null, MallOrder::getRejectReason, reason)
                .set(adminId != null, MallOrder::getReviewBy, adminId)
                .set(adminId != null, MallOrder::getReviewTime, LocalDateTime.now())
                .set(MallOrder::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("该兑换单当前状态不可取消或驳回");
        }
        restoreStockOrWarn(order.getSpecId(), orderId);
        pointService.record(volunteerId, order.getPoints(), PointSourceType.EXCHANGE, null,
                PointSourceType.MALL_REFUND_REQUEST_PREFIX + orderId,
                "兑换退回 " + order.getGoodsName() + "（" + order.getSpecName() + "）",
                PointSourceType.OPERATOR_SYSTEM, null);
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
                return;
            } catch (DuplicateKeyException e) {
                // 撞的一定是 uk_pickup_code——这条语句只写这一个唯一列
                log.warn("取货码碰撞，换一个重试（第 {} 次）", attempt + 1);
            }
        }
        throw new BusinessException("取货码生成失败，请重试");
    }

    /**
     * 现场核销：<b>按取货码，不按订单 id</b>。
     *
     * <p>扫码枪扫出来的是码；做成「先按码查 id、再按 id 核销」会多一次往返，
     * 还把一个本可原子的动作拆成两步。</p>
     *
     * <p><b>一次性由 CAS 保证</b>（{@code status = 待领取} 写进 WHERE）：同一个码连扫两次、
     * 两个窗口同时扫，都只会有一次成功。</p>
     *
     * <p>失败时要说得准：码不存在 / 已核销过（带时间）/ 单子状态不对，是三件不同的事。
     * 一律报「核销失败」会让柜台前的人不知道该不该把东西给出去。</p>
     *
     * @return 核销掉的那张单，供柜台核对该发什么
     */
    @Transactional(rollbackFor = Exception.class)
    public MallOrderVO verify(String rawCode, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        String code = PickupCodeUtil.normalize(rawCode);
        if (!PickupCodeUtil.isValid(code, PickupCodeUtil.DOMAIN_MALL)) {
            // 域标记不符也走这里：拿着证书的取货码到商城柜台，当场就该被拒，不必去表里查一趟
            throw new BusinessException("取货码无效");
        }
        int rows = orderMapper.update(null, Wrappers.<MallOrder>lambdaUpdate()
                .eq(MallOrder::getPickupCode, code)
                .eq(MallOrder::getStatus, MallOrderStatus.READY)
                .set(MallOrder::getStatus, MallOrderStatus.PICKED)
                .set(MallOrder::getPickupTime, LocalDateTime.now())
                .set(MallOrder::getPickupOperator, adminId)
                .set(MallOrder::getUpdateTime, LocalDateTime.now()));
        MallOrder order = orderMapper.selectOne(Wrappers.<MallOrder>lambdaQuery()
                .eq(MallOrder::getPickupCode, code));
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
                        .in(MallOrder::getStatus, MallOrderStatus.READY, MallOrderStatus.PICKED)
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
     * @param forAdmin  管理端才带兑换人 id
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
        vo.setStatus(o.getStatus());
        vo.setStatusLabel(MallOrderStatus.labelOf(o.getStatus()));
        vo.setDeliveryType(o.getDeliveryType());
        vo.setPickupSiteName(o.getPickupSiteName());
        vo.setPickupSiteAddr(o.getPickupSiteAddr());
        vo.setPickupSitePhone(o.getPickupSitePhone());
        vo.setPickupTime(o.getPickupTime());
        vo.setRejectReason(o.getRejectReason());
        vo.setCreateTime(o.getCreateTime());
        if (forAdmin) {
            vo.setVolunteerId(o.getVolunteerId());
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
