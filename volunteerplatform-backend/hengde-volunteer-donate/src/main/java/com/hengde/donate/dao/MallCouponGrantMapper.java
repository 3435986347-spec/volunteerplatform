package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.donate.entity.MallCouponGrant;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * MallCouponGrant Mapper。
 *
 * <p>用卷与归还是两条<b>手写 CAS</b>，不是「先读后改」——理由与库存那条（D7(b)）同一个：
 * 读卷与改卷是同一行，读完紧接着就要改，先取共享锁再升级排他锁就是那句
 * 「先 S 后 X 是锁升级」；把全部判定折进 WHERE、按影响行数判定，一条语句根本不产生这个序列。</p>
 *
 * <p>⚠️ <b>两条都显式写 {@code is_deleted = 0}</b>：{@code @TableLogic} 只对 MyBatis-Plus 生成的语句生效，
 * 手写 {@code @Update} 不会自动补（V3规划 D7(b) 第 5 轮评审补的那一条）。</p>
 *
 * <p>⚠️ <b>「现在」由调用方传入，不用 SQL 的 {@code NOW()}</b>：服务层在发起 CAS 之前会先用 Java 的时间
 * 判一遍、给出准确文案；两处若各取各的时钟，数据库时区与 JVM 时区不一致时（测试容器默认 UTC），
 * 就会出现「Java 说还没过期、库里说过期了」这种只在边界上现形的错判。统一一个时间源。</p>
 *
 * @author hengde
 */
public interface MallCouponGrantMapper extends BaseMapper<MallCouponGrant> {

    /**
     * 把一张卷用在某张兑换单上。影响行数 1 = 用上了，0 = 用不了（不是本人的 / 已用 / 已作废 / 未生效 / 已过期）。
     *
     * <p>「是不是这件商品能用的卷」<b>不在这里判</b>：那要读商品与规格，由服务层在同一事务里先判；
     * 这里只折进「这张卷此刻是否仍可用」这组会被并发改变的条件——双击下两单、两个线程同用一张卷，
     * 都只会有一个拿到 1。</p>
     *
     * @param grantId     卷发放记录 id
     * @param volunteerId 必须是持有人本人
     * @param orderId     用在哪张单上（归还时凭它 CAS）
     * @param now         当前时间（见类注释：统一时间源）
     * @param unused      「未使用」状态码
     * @param used        「已使用」状态码
     */
    @Update("UPDATE mall_coupon_grant SET status = #{used}, used_order_id = #{orderId}, used_time = #{now}, "
            + "update_time = #{now} "
            + "WHERE id = #{grantId} AND volunteer_id = #{volunteerId} AND status = #{unused} "
            + "AND is_deleted = 0 AND valid_start <= #{now} AND expire_time > #{now}")
    int useGrant(@Param("grantId") Long grantId, @Param("volunteerId") Long volunteerId,
                 @Param("orderId") Long orderId, @Param("now") LocalDateTime now,
                 @Param("unused") int unused, @Param("used") int used);

    /**
     * 退单时归还卷。影响行数 1 = 已归还，0 = 没归还。
     *
     * <p><b>条件里带 {@code used_order_id = orderId}</b>：只归还「用在这张单上」的那一次。
     * 否则一张卷被 A 单用过、A 单退回后又被 B 单用上，此时再重放一次 A 的退单就会把 B 正在用的卷抢回来。
     * 幂等同样由它保证——重放时 used_order_id 已被清空，影响行数为 0。</p>
     *
     * <p><b>不检查有效期</b>：卷可能在下单之后到期，但那不妨碍把它还回去——
     * 还回去的是一张「未使用但已过期」的卷，展示为已过期、下次也用不了，账是对的。</p>
     */
    @Update("UPDATE mall_coupon_grant SET status = #{unused}, used_order_id = NULL, used_time = NULL, "
            + "update_time = #{now} "
            + "WHERE id = #{grantId} AND used_order_id = #{orderId} AND status = #{used} AND is_deleted = 0")
    int restoreGrant(@Param("grantId") Long grantId, @Param("orderId") Long orderId,
                     @Param("now") LocalDateTime now, @Param("unused") int unused, @Param("used") int used);

    /**
     * 发卷撞 {@code uk_request_volunteer} 之后取回冲突行，<b>当前读 + 共享锁</b>。
     *
     * <p>与积分账本 {@code PointRecordMapper.selectBySourceForShare} 同一条纪律：RR 下快照读可能看不见对方
     * 刚提交的那一行；<b>必须是 FOR SHARE 不能 FOR UPDATE</b>——报重复键时 InnoDB 已给冲突行加了 S 锁，
     * 多个 loser 再抢 X 会互等成死锁（{@code PointRecordLockOrderTest} 钉死过这一课）。</p>
     *
     * <p><b>刻意不带 {@code is_deleted = 0}</b>：唯一键不含 is_deleted，软删行照样占着键；
     * 这里要找的是「占着这个键的那一行」，过滤掉它就会取不回冲突行、只能报错。</p>
     */
    @Select("SELECT * FROM mall_coupon_grant WHERE request_id = #{requestId} AND volunteer_id = #{volunteerId} "
            + "FOR SHARE")
    MallCouponGrant selectByRequestAndVolunteerForShare(@Param("requestId") String requestId,
                                                       @Param("volunteerId") Long volunteerId);
}
