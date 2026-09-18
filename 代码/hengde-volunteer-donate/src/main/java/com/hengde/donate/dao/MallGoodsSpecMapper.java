package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.donate.entity.MallGoodsSpec;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MallGoodsSpec Mapper。
 *
 * @author hengde
 */
public interface MallGoodsSpecMapper extends BaseMapper<MallGoodsSpec> {

    /**
     * 扣一件库存，<b>同时校验商品此刻仍可下单</b>。影响行数 1=成功，0=拒绝。
     *
     * <p><b>为什么把商品状态折进这条 UPDATE 的 WHERE，而不是先读商品再扣库存</b>：</p>
     *
     * <p>先读后改要读到「最新已提交」的商品状态才有意义（RR 下普通查询是快照读，
     * 看不见别人刚提交的下架），于是得取当前读；而规格这一行<b>读完紧接着就要改</b>——
     * 两个人同时买同一规格时，双方各自先拿到该行的共享锁、再一起抢排他锁，
     * 就是 {@code RewardPunishService} javadoc 里写死过的那句「<b>先 S 后 X 是锁升级，
     * 两名管理员同时审同一个人的两张单会直接死锁</b>」。折进一条语句后压根不产生这个序列。</p>
     *
     * <p><b>选锁的判据是「读完之后改不改这一行」</b>，不是「一律用共享锁」：
     * {@code HonorMedalMapper.selectByIdForShare} 能用 S 是因为它读完只比对、不改勋章那一行
     * （改的是发放记录）；这里要改的正是读的那一行，所以根本不该走「先读后改」。
     * 若将来确有必须先读的场景，正确形态是<b>规格行取 {@code FOR UPDATE}、商品头取
     * {@code FOR SHARE}，且顺序固定为先头后规格</b>——顺序不固定又是一类死锁。</p>
     *
     * <p><b>两个 {@code is_deleted} 都要显式写。</b> {@code @TableLogic} 只对 MyBatis-Plus
     * 自己生成的语句与 wrapper 生效，手写 {@code @Update} 不会自动补：漏掉 {@code s.is_deleted = 0}，
     * <b>软删的规格照样能被扣库存下单</b>（已在真实 MySQL 上验证：去掉该条件后，
     * 一个 is_deleted=1 的规格库存从 5 变成 4）。这与证书样本那条「软删行仍占唯一键」是同一族——
     * <b>软删在手写 SQL 里永远要显式处理</b>。</p>
     *
     * <p>影响行数为 0 时<b>不要直接报「库存不足」</b>：拒绝的原因有五种（无货 / 规格已删 /
     * 商品未上架 / 已隐藏 / 商品已删）。调用方应再补一次只读查询给出准确文案，
     * 见 {@code MallOrderService.explainDeductFailure}。</p>
     *
     * <p><b>数量写死为 1</b>（Row 8 的兑换没有数量控件）——这是显式取舍，理由记在
     * {@code 文档/v3/V3规划.md} 的「商城批」。要支持数量，改的不止这里：
     * 订单表、快照的「花了多少分」、退分金额都得跟着走。</p>
     *
     * @param specId    规格 id
     * @param onSaleStatus 「已上架」的状态码，由调用方传入以免 SQL 里写死魔法数
     * @return 影响行数，1=扣减成功
     */
    @Update("UPDATE mall_goods_spec s JOIN mall_goods g ON g.id = s.goods_id "
            + "SET s.stock = s.stock - 1, s.update_time = NOW() "
            + "WHERE s.id = #{specId} AND s.is_deleted = 0 AND s.stock >= 1 "
            + "AND g.status = #{onSaleStatus} AND g.hidden = 0 AND g.is_deleted = 0 AND g.sponsor_suspended = 0")
    int deductStock(@Param("specId") Long specId, @Param("onSaleStatus") int onSaleStatus);

    /**
     * 归还一件库存。用于驳回 / 取消。影响行数 1=已归还，0=没归还（两种原因，见下）。
     *
     * <p><b>幂等不靠这条语句，靠调用方的订单状态 CAS</b>：只有把订单从「待审核」
     * 原子地改成「已驳回/已取消」成功的那一次才会走到这里，双击驳回与回调重投都只会成功一次。
     * 这与退分是同一条纪律，且两者必须<b>同事务</b>——只还库存不退分，或反过来，都是账实分离。</p>
     *
     * <p>这里<b>不校验商品状态</b>：商品可能在下单之后被下架，但那不该妨碍把库存还回去。</p>
     *
     * <p><b>{@code is_deleted = 0} 那一条会让影响行数变成 0，这是有意为之</b>：
     * 规格已被软删时，库存字段本身已无消费方（下单侧的 {@code deductStock} 同样带
     * {@code s.is_deleted = 0}，永远扣不到它），把数字加回去没有任何意义，
     * 反而会在「删了又恢复」的场景里留下一个来路不明的余量。</p>
     *
     * <p><b>但影响行数不能就这么丢掉</b>——0 行有两种原因，性质完全不同：规格<b>已软删</b>
     * 是正常运营（照上一段，不还就对了）；规格<b>根本不存在</b>是数据完整性问题
     * （订单挂着一个不存在的 specId，本表没有外键，落库时也没人拦）。两者都无声，
     * 后者就永远没人知道。故调用方拿到 0 必须记 warn，并用
     * {@link #selectDeletedFlag} 区分是哪一种——见 {@code MallOrderService.refundOrder}。
     * <b>不抛异常</b>：抛了会连退分一起回滚，志愿者会因为管理员删过一个规格而永远取消不了单，
     * 那比少还一件库存严重得多。</p>
     *
     * @param specId 规格 id
     * @return 影响行数，1=已归还
     */
    @Update("UPDATE mall_goods_spec SET stock = stock + 1, update_time = NOW() "
            + "WHERE id = #{specId} AND is_deleted = 0")
    int restoreStock(@Param("specId") Long specId);

    /**
     * 读软删标记，<b>不受 {@code @TableLogic} 过滤</b>——纯诊断用。
     *
     * <p>{@code selectById} 会把软删行当作不存在（返回 null），所以它区分不了
     * 「已软删」与「根本不存在」；而 {@link #restoreStock} 返回 0 时，恰恰只有这两种可能。
     * 手写 {@code @Select} 是唯一能看见软删行的读法。</p>
     *
     * @param specId 规格 id
     * @return 1=已软删，0=在（那就是并发把它删了又恢复之类的怪事），null=该行根本不存在
     */
    @Select("SELECT is_deleted FROM mall_goods_spec WHERE id = #{specId}")
    Integer selectDeletedFlag(@Param("specId") Long specId);
}
