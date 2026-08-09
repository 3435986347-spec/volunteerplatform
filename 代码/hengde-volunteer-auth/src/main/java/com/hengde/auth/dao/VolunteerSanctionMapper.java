package com.hengde.auth.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.auth.entity.VolunteerSanction;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 处置措施 Mapper。
 *
 * @author hengde
 */
public interface VolunteerSanctionMapper extends BaseMapper<VolunteerSanction> {

    /**
     * 解除某张奖惩单产生的<b>全部生效中</b>处置（CAS：只动 status = 1 的行）。
     *
     * <p>用于申诉成立与管理员撤销。写成一条 UPDATE 而不是「查出来逐条改」：
     * 后者在并发下会把别人刚写入的处置漏掉，也可能把已解除的重复解除一次、
     * 把 {@code lifted_time} 覆盖成第二次的时刻。</p>
     *
     * @return 实际解除的条数；0 = 本来就没有生效中的处置
     */
    @Update("UPDATE volunteer_sanction SET status = 2, lifted_by = #{operatorId}, lifted_time = NOW(), "
            + "lift_reason = #{reason}, update_time = NOW() "
            + "WHERE source_type = #{sourceType} AND source_id = #{sourceId} "
            + "AND status = 1 AND is_deleted = 0")
    int liftBySource(@Param("sourceType") int sourceType, @Param("sourceId") Long sourceId,
                     @Param("operatorId") Long operatorId, @Param("reason") String reason);

    /**
     * 闸门专用：取一条正在挡住该能力域的处置，<b>{@code FOR SHARE} 当前读</b>。必须在事务内调用。
     *
     * <p><b>为什么闸门不能用普通查询</b>：REPEATABLE READ 的读视图在事务的<b>第一次一致性读</b>
     * 就定死了，此后普通 SELECT 永远读不到别人后来提交的行。锁住志愿者父行只解决了<b>串行化</b>
     * （对方提交不完，我们就等着），解决不了<b>可见性</b>——等到了，读的还是那个旧快照。</p>
     *
     * <p>这不是理论问题：{@code EnrollmentService.doProxyEnroll} 出于隐私要求，必须先跑
     * {@code requireSameActiveGroup}（普通读）再过闸门，读视图在那一刻就已经建立；
     * 之后无论父行锁等多久，普通查询都看不见这期间提交的处罚。
     * 而且这个前提极脆：任何人在闸门之前加一条读，闸门就静默退化，没有任何征兆。</p>
     *
     * <p><b>两把锁各管一段，缺一不可</b>：</p>
     * <ul>
     *   <li>本方法的 {@code FOR SHARE} 负责<b>可见性</b>（绕开读视图，读最新已提交），
     *       并且在 RR 下连<b>间隙</b>一起锁，因此从「处置行被插入」那一刻起也能挡住写入方；</li>
     *   <li>父行 S 锁负责<b>插入之前那一段</b>。写入方（{@code RewardPunishService.approve}）
     *       先做 CAS 改单、再入积分流水，最后才 {@code impose} 插处置行——在它插进去之前，
     *       本方法的间隙锁与它毫无冲突，闸门会读到「没有处罚」并放行。
     *       而 {@code impose} 的第一件事就是取父行 X 锁，所以闸门只要也取父行 S，
     *       就把串行化边界前移到了写入方开工的那一刻。
     *       {@code EnrollmentGateIsolationTest.gate_blocksWhileWriterHoldsParentRowLock}
     *       专门制造这个窗口：写入方只持父行锁、还没插行，删掉闸门的父行 S 锁用例即红。</li>
     * </ul>
     *
     * <p>（早先注释把父行锁的理由写成「锁不住不存在的行」，那话容易误导：InnoDB 的间隙锁恰恰能挡住
     * 「尚未存在的行」被插入。父行锁真正买到的是<b>更早的那一段窗口</b>，不是「间隙锁做不到」。）</p>
     *
     * <p>取 S 不会引发升级：调用方（报名/补录/代报名/签到）之后写的是报名与考勤，不改这张表。
     * 与 {@code SanctionService.impose} 也不会死锁——impose 必须先拿志愿者行的 X 锁，
     * 而那把锁被闸门的 S 占着，它根本走不到插入这一步。</p>
     *
     * <p>排序理由见 {@code SanctionQueryService.findBlocking}：{@code expire_time IS NULL}
     * （不设期限）最重，必须排最前，否则永久处置会被报成「至 X 自动解除」，正好说反。</p>
     *
     * @param now 判定时刻，由调用方传入，保证同一次判定里各条件用的是同一个时刻
     */
    @Select("SELECT * FROM volunteer_sanction "
            + "WHERE is_deleted = 0 AND volunteer_id = #{volunteerId} AND status = 1 "
            + "AND scope IN (#{scope}, #{allScope}) "
            + "AND effective_time <= #{now} "
            + "AND (expire_time IS NULL OR expire_time > #{now}) "
            + "ORDER BY expire_time IS NULL DESC, expire_time DESC LIMIT 1 FOR SHARE")
    VolunteerSanction selectBlockingForShare(@Param("volunteerId") Long volunteerId,
                                             @Param("scope") int scope,
                                             @Param("allScope") int allScope,
                                             @Param("now") LocalDateTime now);
}
