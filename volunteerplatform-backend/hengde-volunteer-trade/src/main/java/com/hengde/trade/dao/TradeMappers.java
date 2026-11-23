package com.hengde.trade.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.trade.entity.TradeOrder;
import com.hengde.trade.entity.TradePayment;
import com.hengde.trade.entity.TradeReconcileRun;
import com.hengde.trade.entity.TradeRefund;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 收付各表的 Mapper（V3 trade 批）。放在一个文件里的理由同 {@code DonatePairMappers}：
 * 它们是同一条链路上的三段，字段互相引用，分成三个文件读的人要来回跳。
 *
 * @author hengde
 */
public final class TradeMappers {

    private TradeMappers() {
    }

    /** 交易单。 */
    @Mapper
    public interface TradeOrderMapper extends BaseMapper<TradeOrder> {

        /**
         * 按我方单号取交易单，<b>当前读 + 排他锁</b>——回调、主动查单、扫描任务三条路都从这里进来，
         * 读完都要改这一行。
         *
         * <p>⚠️ 必须是当前读：RR 下快照在事务第一次 SELECT 就定死，
         * 三条路并发时后到的那条会读到改之前的状态，把「已支付」当成「待支付」再处理一遍。</p>
         */
        @Select("SELECT * FROM trade_order WHERE out_trade_no = #{outTradeNo} AND is_deleted = 0 FOR UPDATE")
        TradeOrder selectByOutTradeNoForUpdate(@Param("outTradeNo") String outTradeNo);

        /** 按 id 的当前读（后台关单 / 退款用）。 */
        @Select("SELECT * FROM trade_order WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
        TradeOrder selectByIdForUpdate(@Param("id") Long id);

        /**
         * 置为已支付：<b>条件写进 WHERE，按影响行数判定</b>。
         * 只有「待支付」才会被改，于是重复回调、查单与扫描撞在一起时只有一条生效。
         */
        @Update("UPDATE trade_order SET status = #{paid}, pay_time = #{payTime}, transaction_id = #{transactionId}, "
                + "update_time = #{now} WHERE id = #{id} AND status = #{pending} AND is_deleted = 0")
        int markPaid(@Param("id") Long id, @Param("transactionId") String transactionId,
                     @Param("payTime") LocalDateTime payTime, @Param("now") LocalDateTime now,
                     @Param("pending") int pending, @Param("paid") int paid);

        /** 关单：只有「待支付」能关——已支付的单关掉等于把钱吞了。 */
        @Update("UPDATE trade_order SET status = #{closed}, close_time = #{now}, update_time = #{now} "
                + "WHERE id = #{id} AND status = #{pending} AND is_deleted = 0")
        int close(@Param("id") Long id, @Param("now") LocalDateTime now,
                  @Param("pending") int pending, @Param("closed") int closed);

        /**
         * 累加已退金额并据此改状态。
         *
         * <p>⚠️ <b>MySQL 的 UPDATE 赋值自左向右、后面的表达式看得到前面刚改过的值</b>：
         * 所以 `CASE` 里的 {@code refunded_amount} <b>已经包含</b>这一笔。这一点是刻意利用的，
         * 但也正因为容易看走眼才写在这里——别把两句的顺序调过来。</p>
         *
         * <p>上限 {@code refunded_amount + ? <= amount} 与「只有已支付 / 部分退款的单能退」一起写进 WHERE：
         * 退过头这种事必须在写入语句里挡住，不能只靠调用方先查一次。</p>
         */
        @Update("UPDATE trade_order SET refunded_amount = refunded_amount + #{refundFen}, "
                + "status = CASE WHEN refunded_amount >= amount THEN #{refunded} ELSE #{partial} END, "
                + "update_time = #{now} "
                + "WHERE id = #{id} AND is_deleted = 0 AND status IN (#{paid}, #{partial}) "
                + "AND refunded_amount + #{refundFen} <= amount")
        int addRefunded(@Param("id") Long id, @Param("refundFen") int refundFen, @Param("now") LocalDateTime now,
                        @Param("paid") int paid, @Param("partial") int partial, @Param("refunded") int refunded);

        /**
         * 扫描：捞「待支付、下单已超过冷却时间」的单去主动查单（D2 的第二道）。
         *
         * <p>走 {@code idx_status_expire}，扫描集合只含未终态的单、不随历史单量增长
         * （与 V39 {@code idx_reminder_pending}、V50 {@code idx_track_pending} 同形）。
         * 冷却时间是为了不去查刚下还没人付的单。</p>
         */
        @Select("SELECT * FROM trade_order WHERE status = #{pending} AND is_deleted = 0 "
                + "AND create_time <= #{before} ORDER BY id LIMIT #{limit}")
        List<TradeOrder> selectDue(@Param("pending") int pending, @Param("before") LocalDateTime before,
                                   @Param("limit") int limit);

        /** 对账用：某段时间内本地记为已支付的单。 */
        @Select("SELECT * FROM trade_order WHERE status IN (#{paid}, #{partial}, #{refunded}) AND is_deleted = 0 "
                + "AND pay_time >= #{from} AND pay_time < #{to} ORDER BY id")
        List<TradeOrder> selectPaidBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                           @Param("paid") int paid, @Param("partial") int partial,
                                           @Param("refunded") int refunded);

        /**
         * 对账用：某段时间内关掉的单。
         *
         * <p><b>「钱收了、单没发货」真正落脚的地方在这里，不在已支付那一侧</b>：用户在关单的边界上付款、
         * 或者后台关单后渠道侧关单失败，钱会付进一张本地已关闭的单——只核对已支付的单永远看不见它。</p>
         */
        @Select("SELECT * FROM trade_order WHERE status = #{closed} AND is_deleted = 0 "
                + "AND close_time >= #{from} AND close_time < #{to} ORDER BY id")
        List<TradeOrder> selectClosedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                             @Param("closed") int closed);
    }

    /** 支付流水。 */
    @Mapper
    public interface TradePaymentMapper extends BaseMapper<TradePayment> {

        /**
         * 撞了 {@code uk_transaction} 之后把赢家读回来：<b>当前读 + 共享锁</b>。
         *
         * <p>用 {@code FOR SHARE} 不用 {@code FOR UPDATE}——报重复键时 InnoDB 已给冲突行加了 S 锁，
         * 多个 loser 再抢 X 会互等成死锁（{@code PointRecordLockOrderTest} 钉死过这一课）。</p>
         */
        @Select("SELECT * FROM trade_payment WHERE transaction_id = #{transactionId} AND is_deleted = 0 FOR SHARE")
        TradePayment selectByTransactionForShare(@Param("transactionId") String transactionId);

        /** 普通读（列表 / 详情用，不取 raw_json）。 */
        @Select("SELECT id, trade_order_id, transaction_id, amount, payer_openid, success_time, source, "
                + "create_time, update_time, is_deleted FROM trade_payment "
                + "WHERE trade_order_id = #{orderId} AND is_deleted = 0 ORDER BY id")
        List<TradePayment> selectByOrder(@Param("orderId") Long orderId);

        @Select("SELECT COUNT(*) FROM trade_payment WHERE trade_order_id = #{orderId} AND is_deleted = 0")
        int countByOrder(@Param("orderId") Long orderId);
    }

    /** 退款流水。 */
    @Mapper
    public interface TradeRefundMapper extends BaseMapper<TradeRefund> {

        @Select("SELECT * FROM trade_refund WHERE out_refund_no = #{outRefundNo} AND is_deleted = 0 FOR UPDATE")
        TradeRefund selectByOutRefundNoForUpdate(@Param("outRefundNo") String outRefundNo);

        @Select("SELECT id, trade_order_id, out_refund_no, refund_id, amount, status, reason, operator_id, "
                + "success_time, create_time, update_time, is_deleted FROM trade_refund "
                + "WHERE trade_order_id = #{orderId} AND is_deleted = 0 ORDER BY id")
        List<TradeRefund> selectByOrder(@Param("orderId") Long orderId);

        /** 退款结果回写：只有「处理中」会被改，重复回调只有一条生效。 */
        @Update("UPDATE trade_refund SET status = #{to}, refund_id = #{refundId}, success_time = #{successTime}, "
                + "update_time = #{now} WHERE id = #{id} AND status = #{processing} AND is_deleted = 0")
        int applyResult(@Param("id") Long id, @Param("refundId") String refundId,
                        @Param("successTime") LocalDateTime successTime, @Param("now") LocalDateTime now,
                        @Param("processing") int processing, @Param("to") int to);
    }

    /** 对账记录。 */
    @Mapper
    public interface TradeReconcileRunMapper extends BaseMapper<TradeReconcileRun> {
    }
}
