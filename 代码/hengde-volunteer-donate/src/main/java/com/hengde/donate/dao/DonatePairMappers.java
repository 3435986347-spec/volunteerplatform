package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.donate.entity.DonateCrowdfund;
import com.hengde.donate.entity.DonatePairLetter;
import com.hengde.donate.entity.DonatePairProject;
import com.hengde.donate.entity.DonatePairRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 结对与众筹的 Mapper（V3 结对批）。四个接口放在一个文件里的理由同 {@code DonateSimpleMappers}：
 * 三个是纯 {@code BaseMapper}，只有结对登记需要手写语句，分成四个文件读的人要来回跳。
 *
 * @author hengde
 */
public final class DonatePairMappers {

    private DonatePairMappers() {
    }

    /** 结对项目。 */
    @Mapper
    public interface DonatePairProjectMapper extends BaseMapper<DonatePairProject> {

        /**
         * 认捐额累加（结对成立时）。<b>两个条件都写进 WHERE，按影响行数判定</b>：
         * 项目仍在「进行中」，且<b>加完之后不超过受助金额</b>。
         *
         * <p>上限这一条是必须的：登记时校验的是「当时的缺口」，而认捐额只在<b>确认成立</b>时才累加——
         * 两个人各自登记了不超过缺口的金额，两笔都确认，加起来就越过了受助金额。
         * 校验放在登记那一刻挡不住这件事，只有写入语句自带上限才挡得住。</p>
         */
        @Update("UPDATE donate_pair_project SET pledged_amount = pledged_amount + #{amount}, update_time = #{now} "
                + "WHERE id = #{id} AND status = #{open} AND is_deleted = 0 "
                + "AND pledged_amount + #{amount} <= target_amount")
        int addPledged(@Param("id") Long id, @Param("amount") BigDecimal amount,
                       @Param("now") java.time.LocalDateTime now, @Param("open") int open);

        /**
         * 认捐额回退（已成立的结对被取消时）。
         *
         * <p><b>带 {@code pledged_amount >= amount} 的护栏</b>：金额是无符号语义，
         * 回退到负数说明账已经错了，与其把错误写进库，不如让影响行数为 0、由调用方抛出来。</p>
         */
        @Update("UPDATE donate_pair_project SET pledged_amount = pledged_amount - #{amount}, update_time = #{now} "
                + "WHERE id = #{id} AND pledged_amount >= #{amount} AND is_deleted = 0")
        int subtractPledged(@Param("id") Long id, @Param("amount") BigDecimal amount,
                            @Param("now") java.time.LocalDateTime now);

        /**
         * 已到账累加（捐款批）。<b>不设上限、不看项目状态</b>：钱已经进来了，项目结束了也得记上——
         * 拒绝记账不会让钱消失，只会让账对不上。
         */
        @Update("UPDATE donate_pair_project SET raised_amount = raised_amount + #{amount}, update_time = #{now} "
                + "WHERE id = #{id}")
        int addRaised(@Param("id") Long id, @Param("amount") BigDecimal amount,
                      @Param("now") java.time.LocalDateTime now);

        /** 已到账回退（退款）。护栏同认捐额：回退到负数说明账已经错了，让影响行数为 0 由调用方抛出。 */
        @Update("UPDATE donate_pair_project SET raised_amount = raised_amount - #{amount}, update_time = #{now} "
                + "WHERE id = #{id} AND raised_amount >= #{amount}")
        int subtractRaised(@Param("id") Long id, @Param("amount") BigDecimal amount,
                           @Param("now") java.time.LocalDateTime now);

        /**
         * 各项目的「参加人数」：有效登记（待确认 + 已成立）的人数。
         *
         * <p><b>不在项目表上存这一列</b>——存了就有两个口径，而本项目已经在
         * {@code points_award} 与积分账本、{@code totalEarned} 与排行榜上各栽过一次。</p>
         */
        @Select("<script>SELECT project_id AS projectId, COUNT(*) AS cnt FROM donate_pair_record "
                + "WHERE is_deleted = 0 AND status IN (#{registered}, #{established}) AND project_id IN "
                + "<foreach collection='projectIds' item='p' open='(' separator=',' close=')'>#{p}</foreach>"
                + " GROUP BY project_id</script>")
        List<Map<String, Object>> countParticipants(@Param("projectIds") Collection<Long> projectIds,
                                                    @Param("registered") int registered,
                                                    @Param("established") int established);
    }

    /** 结对登记。 */
    @Mapper
    public interface DonatePairRecordMapper extends BaseMapper<DonatePairRecord> {

        /**
         * 「这个人在这个项目上的那条活登记」——<b>普通读，不加锁</b>。
         *
         * <p>⚠️ <b>这里刻意不用 {@code FOR UPDATE}</b>：那条登记<b>可能还不存在</b>，而对不存在的键做当前读
         * 会在 {@code uk_active_pair} 上留下<b>间隙锁</b>。同一个项目下不同的人（键相邻）并发登记时，
         * 各自的 INSERT 都要在对方持有的间隙里取插入意向锁，互等成环——压测里 480 次操作就撞出了
         * {@code DeadlockLoserDataAccessException}。<b>不存在的行锁不住</b>，
         * 挡重复登记的是唯一键本身，不是这次查询。</p>
         */
        @Select("SELECT * FROM donate_pair_record WHERE active_pair_key = CONCAT(#{projectId}, ':', #{volunteerId}) "
                + "AND is_deleted = 0")
        DonatePairRecord selectActive(@Param("projectId") Long projectId,
                                      @Param("volunteerId") Long volunteerId);

        /**
         * 撞了 {@code uk_active_pair} 之后取回赢家那一行：<b>当前读 + 共享锁</b>。
         *
         * <p>RR 下快照在事务第一次 SELECT 就定死，普通读看不见赢家后来提交的行，报错会变成
         * 「登记失败」这种说不清的话。用 {@code FOR SHARE} 不用 {@code FOR UPDATE}——报重复键时
         * InnoDB 已给冲突行加了 S 锁，多个 loser 再抢 X 会互等成死锁（积分账本那条课已钉死）。</p>
         */
        @Select("SELECT * FROM donate_pair_record WHERE active_pair_key = CONCAT(#{projectId}, ':', #{volunteerId}) "
                + "AND is_deleted = 0 FOR SHARE")
        DonatePairRecord selectActiveForShare(@Param("projectId") Long projectId,
                                              @Param("volunteerId") Long volunteerId);

        /** 按 id 的当前读（后台确认 / 取消用；读完要改这一行，故取排他锁）。 */
        @Select("SELECT * FROM donate_pair_record WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
        DonatePairRecord selectByIdForUpdate(@Param("id") Long id);

        /** 结对已付累加（捐款批）。不设上限：理由同项目的已到账。 */
        @Update("UPDATE donate_pair_record SET paid_amount = paid_amount + #{amount}, update_time = #{now} "
                + "WHERE id = #{id}")
        int addPaid(@Param("id") Long id, @Param("amount") BigDecimal amount,
                    @Param("now") java.time.LocalDateTime now);

        @Update("UPDATE donate_pair_record SET paid_amount = paid_amount - #{amount}, update_time = #{now} "
                + "WHERE id = #{id} AND paid_amount >= #{amount}")
        int subtractPaid(@Param("id") Long id, @Param("amount") BigDecimal amount,
                         @Param("now") java.time.LocalDateTime now);
    }

    /** 受助方来信。 */
    @Mapper
    public interface DonatePairLetterMapper extends BaseMapper<DonatePairLetter> {
    }

    /** 众筹项目。 */
    @Mapper
    public interface DonateCrowdfundMapper extends BaseMapper<DonateCrowdfund> {

        /** 已筹累加（捐款批）。不设上限、不看项目状态：钱已经进来了，超出目标或项目刚结束都得记上。 */
        @Update("UPDATE donate_crowdfund SET raised_amount = raised_amount + #{amount}, update_time = #{now} "
                + "WHERE id = #{id}")
        int addRaised(@Param("id") Long id, @Param("amount") BigDecimal amount,
                      @Param("now") java.time.LocalDateTime now);

        @Update("UPDATE donate_crowdfund SET raised_amount = raised_amount - #{amount}, update_time = #{now} "
                + "WHERE id = #{id} AND raised_amount >= #{amount}")
        int subtractRaised(@Param("id") Long id, @Param("amount") BigDecimal amount,
                           @Param("now") java.time.LocalDateTime now);
    }
}
