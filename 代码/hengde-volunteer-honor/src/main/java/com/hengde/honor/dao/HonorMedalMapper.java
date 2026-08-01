package com.hengde.honor.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.honor.entity.HonorMedal;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * HonorMedal Mapper。
 *
 * @author hengde
 */
public interface HonorMedalMapper extends BaseMapper<HonorMedal> {

    /**
     * 按 id 取勋章并加<b>共享行锁</b>（{@code FOR SHARE}），必须在事务内调用。
     *
     * <p><b>为什么普通 {@code selectById} 不够</b>：发放审核（{@code MedalGrantService.approve}）
     * 与发起发放（{@code apply}）都要先确认「勋章此刻仍是已启用」。这两个方法都在事务里，
     * 而 InnoDB 默认 REPEATABLE READ 下普通查询是<b>快照读</b>——事务里第一条 SELECT 就把读视图定死了，
     * 此后即使另一个事务把勋章停用/退回重审并提交，这里仍会读到旧的「已启用」，
     * 于是一枚已被撤下的勋章照样生效、照样发分。当前读绕过快照，读到的是最新已提交值。</p>
     *
     * <p><b>为什么是 {@code FOR SHARE} 而不是 {@code FOR UPDATE}</b>：这两处读完之后
     * <b>都不改勋章这一行</b>（改的是发放记录），判断标准与
     * {@code PointRecordMapper.selectBySourceForShare} 一致——读完不改就用共享锁。
     * 好处是多个发放审核可以并行推进，只与真正要改勋章的动作（停用 / 修改 / 删除，它们
     * 都要排他锁）互斥；用排他锁则两个审核之间也会互相排队。</p>
     *
     * @param id 勋章 id
     * @return 勋章行；不存在或已逻辑删除返回 null
     */
    @Select("SELECT * FROM honor_medal WHERE id = #{id} AND is_deleted = 0 FOR SHARE")
    HonorMedal selectByIdForShare(@Param("id") Long id);

    /**
     * 按 id 取勋章并加<b>排他行锁</b>（{@code FOR UPDATE}），必须在事务内调用。
     *
     * <p>供 {@code MedalService.delete} 用：删除是「查有没有发放记录 → 逻辑删除」这样一段
     * 读改写，而 {@code apply} 是「查勋章是否启用 → 插入发放记录」。两者交错时，
     * 可以先删掉勋章、再插进一条指向它的发放记录（V28 没有外键，数据库不会拦），
     * 志愿者的「我的勋章」就会出现一个查不到定义的空白项。删除侧取排他锁、发起侧取共享锁，
     * 两段各自被串行化到对方之外。</p>
     *
     * @param id 勋章 id
     * @return 勋章行；不存在或已逻辑删除返回 null
     */
    @Select("SELECT * FROM honor_medal WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    HonorMedal selectByIdForUpdate(@Param("id") Long id);
}
