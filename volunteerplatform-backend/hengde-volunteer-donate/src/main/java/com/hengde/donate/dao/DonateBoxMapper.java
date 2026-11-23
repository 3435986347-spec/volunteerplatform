package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.donate.entity.DonateBox;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * DonateBox Mapper。
 *
 * @author hengde
 */
public interface DonateBoxMapper extends BaseMapper<DonateBox> {

    /**
     * 装箱 / 出箱前对箱子取<b>共享锁</b>（当前读）。
     *
     * <p><b>为什么是 S</b>——判据仍是「读完之后改不改这一行」：装箱只比对箱子「还在装箱中吗、属于哪个活动」，
     * 改的是物资那一行，不改箱子，所以取共享锁。它与送达（对箱子取排他锁）互斥，
     * 于是「装进一只正在被送走的箱子」这个交错不存在。</p>
     *
     * <p><b>为什么必须先锁箱子、再锁物资</b>（压测撞出来的一条）：流水线上所有碰箱子的动作一律
     * <b>先箱后物</b>。早先装箱写成一条 {@code UPDATE donate_item i JOIN donate_box b}，
     * 实际加锁顺序是「先物资 X、再箱子 S」；而送达是「先箱子 X、再箱内物资 X」——
     * 两个顺序相反，{@code DonateBoxConcurrencyTest} 的装箱与送达赛跑稳定撞出 {@code ER_LOCK_DEADLOCK}。</p>
     */
    @Select("SELECT * FROM donate_box WHERE id = #{id} AND is_deleted = 0 FOR SHARE")
    DonateBox selectByIdForShare(@Param("id") Long id);
}
