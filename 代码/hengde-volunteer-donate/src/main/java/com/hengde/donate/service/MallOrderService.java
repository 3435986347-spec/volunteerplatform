package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.service.PointService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.donate.constant.MallDeliveryType;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.constant.MallOrderStatus;
import com.hengde.donate.dao.MallGoodsMapper;
import com.hengde.donate.dao.MallGoodsSpecMapper;
import com.hengde.donate.dao.MallOrderMapper;
import com.hengde.donate.entity.MallGoods;
import com.hengde.donate.entity.MallGoodsSpec;
import com.hengde.donate.entity.MallOrder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

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

    private MallOrderMapper orderMapper;
    private MallGoodsMapper goodsMapper;
    private MallGoodsSpecMapper specMapper;
    private PointService pointService;
    private RedissonClient redissonClient;
    private TransactionTemplate transactionTemplate;

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
