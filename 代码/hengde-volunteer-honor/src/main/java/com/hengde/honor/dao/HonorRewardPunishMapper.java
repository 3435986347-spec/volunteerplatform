package com.hengde.honor.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.honor.entity.HonorRewardPunish;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 奖惩单 Mapper。
 *
 * @author hengde
 */
public interface HonorRewardPunishMapper extends BaseMapper<HonorRewardPunish> {

    /**
     * 按 id 取单——<b>{@code FOR UPDATE} 当前读</b>，供「审核 / 受理申诉」这类
     * 「先读状态、再据此动作」的路径使用。
     *
     * <p><b>为什么不能用普通快照读</b>：这些方法都在事务里，RR 下快照在事务第一次 SELECT
     * 就定死了，读不到别人后来提交的状态变更。两名管理员同时打开同一张单，
     * 一个刚把它审掉、另一个仍会读到「待审核」而继续往下走。</p>
     *
     * <p><b>为什么是 {@code FOR UPDATE} 而不是 {@code FOR SHARE}</b>：本方法之后<b>紧跟着就要 UPDATE 同一行</b>。
     * 取 S 再升 X 时，两个并发审核各持一把 S、各等对方放掉，直接死锁（ERROR 1213）——
     * 那会让本该是「已被审核」的业务冲突变成一个 500。
     * <b>不要照搬证书那条「用 FOR SHARE」的结论</b>：那里 S 锁是失败的 INSERT 已经加上的、
     * 再取 X 才是升级；这里 S 是自己取的，一开始就取 X 反而不产生升级。
     * 形状不同，结论相反——本批一开始就是照搬错了。</p>
     */
    @Select("SELECT * FROM honor_reward_punish WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    HonorRewardPunish selectByIdForUpdate(@Param("id") Long id);
}
