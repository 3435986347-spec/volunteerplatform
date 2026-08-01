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
}
