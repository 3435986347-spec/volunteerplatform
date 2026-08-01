package com.hengde.honor.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.honor.entity.HonorRankingSnapshot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 排行榜快照 Mapper。
 *
 * @author hengde
 */
public interface HonorRankingSnapshotMapper extends BaseMapper<HonorRankingSnapshot> {

    /**
     * <b>物理</b>删除某周期某板块的全部快照行，供强制补跑时先清空再重写。
     *
     * <p><b>为什么不能用 MyBatis-Plus 的逻辑删除</b>：唯一键 {@code uk_period_rank_volunteer}
     * 不含 {@code is_deleted} 列，逻辑删除只是把行的标记改成 1、行本身还占着那个键，
     * 紧接着的重写就会撞唯一键失败。快照是可重算的派生数据，没有留档价值，物理删除是正确选择。</p>
     *
     * @param periodType 周期码
     * @param periodKey  周期标识
     * @param rankType   榜单码
     * @return 删除行数
     */
    @Delete("DELETE FROM honor_ranking_snapshot WHERE period_type = #{periodType} "
            + "AND period_key = #{periodKey} AND rank_type = #{rankType}")
    int deletePeriod(@Param("periodType") int periodType,
                     @Param("periodKey") String periodKey,
                     @Param("rankType") int rankType);
}
