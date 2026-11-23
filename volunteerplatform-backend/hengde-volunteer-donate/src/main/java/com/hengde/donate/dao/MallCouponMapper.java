package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.donate.entity.MallCoupon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * MallCoupon Mapper。
 *
 * @author hengde
 */
public interface MallCouponMapper extends BaseMapper<MallCoupon> {

    /**
     * 发卷时取卷定义用<b>当前读 + 共享锁</b>。
     *
     * <p><b>为什么是 S 不是 X</b>——判据是「读完之后改不改这一行」：发卷读卷定义只为比对
     * （是否启用、是否已过有效期、取条款快照），改的是发放记录表，不改卷定义这一行，所以取共享锁
     * （同 {@code HonorMedalMapper.selectByIdForShare}）。</p>
     *
     * <p><b>为什么要当前读</b>：RR 下事务里第一条普通 SELECT 就把读视图定死，看不见别人随后提交的「停用」；
     * 取了 S 锁之后，并发的停用 / 修改（取 X）要等本次发放提交才能落下——「停用只挡新发放」这句话才成立。</p>
     */
    @Select("SELECT * FROM mall_coupon WHERE id = #{id} AND is_deleted = 0 FOR SHARE")
    MallCoupon selectByIdForShare(@Param("id") Long id);
}
