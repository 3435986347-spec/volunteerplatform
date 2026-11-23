package com.hengde.auth.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.auth.entity.Volunteer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 志愿者 Mapper。继承 {@link BaseMapper} 即得基础 CRUD；
 * 按 openid / id_card_hash / phone_hash 的查询用条件构造器在 service 里组织。
 *
 * @author hengde
 */
@Mapper
public interface VolunteerMapper extends BaseMapper<Volunteer> {

    /**
     * 按 id 取志愿者并加<b>共享行锁</b>（{@code FOR SHARE}），必须在事务内调用。
     *
     * <p><b>给「授予权益的那一刻再确认一次资格」用</b>。典型是勋章发放审核
     * （{@code MedalGrantService.approve}）：发起时校验过实名与账号状态，但权益
     * （勋章生效 + 积分入账）真正产生是在审核那一刻，两者之间可能隔着好几天。
     * 期间志愿者被禁用、注销或删除，普通 {@code selectById} 在 REPEATABLE READ 下读的是
     * 本事务快照，照样返回「正常」，于是权益仍会发出去。</p>
     *
     * <p>用 {@code FOR SHARE} 而非 {@code FOR UPDATE}：读完不改志愿者这一行（改的是发放记录
     * 与积分流水），与 {@code HonorMedalMapper.selectByIdForShare} 同一判断标准——
     * 读完不改就用共享锁，多个审核可以并行，只与真正要改志愿者状态的动作互斥。</p>
     *
     * @param id 志愿者 id
     * @return 志愿者行；不存在或已逻辑删除返回 null
     */
    @Select("SELECT * FROM volunteer WHERE id = #{id} AND is_deleted = 0 FOR SHARE")
    Volunteer selectByIdForShare(@Param("id") Long id);

    /**
     * 按 id 取志愿者并加<b>排他行锁</b>（{@code FOR UPDATE}），必须在事务内调用。
     *
     * <p><b>用途一：审核奖惩单时复核资格</b>。与 {@link #selectByIdForShare} 同为当前读，
     * 但这条读完之后紧接着要写 {@code volunteer_sanction}，而处置写入本身也要锁这一行
     * （见用途二）。若复核先取 S、施加处置再取 X，就是一次锁升级：两名管理员同时审同一个人的
     * 两张单，各持一把 S、各等对方放掉，直接死锁（ERROR 1213）。一开始就取 X 反而不产生升级。</p>
     *
     * <p><b>用途二：把志愿者这一行当作「处置」的串行化父行</b>。处置的写入端是
     * {@code SanctionService.impose}，执行端是报名/补录/代报名/签到那几道闸门，
     * 两边分处不同事务、也不共用 Redisson 锁（{@code lock:enroll:volunteer:} 只锁报名自己）。
     * 于是「闸门查无处罚 → 处罚提交 → 报名提交」这个窗口无人看守。
     * {@code volunteer_sanction} 里那条记录在闸门查的时候还不存在，锁不住不存在的行；
     * 所以两边约定去锁一行<b>必定存在且稳定</b>的父行：写入端取 X、闸门取 S，
     * 谁先谁后都由数据库排成先后，不再有交叠。</p>
     *
     * @param id 志愿者 id
     * @return 志愿者行；不存在或已逻辑删除返回 null
     */
    @Select("SELECT * FROM volunteer WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    Volunteer selectByIdForUpdate(@Param("id") Long id);
}
