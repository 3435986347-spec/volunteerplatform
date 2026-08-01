package com.hengde.honor.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.honor.entity.HonorRankingSnapshotBatch;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 排行榜快照完成标记 Mapper。
 *
 * @author hengde
 */
public interface HonorRankingSnapshotBatchMapper extends BaseMapper<HonorRankingSnapshotBatch> {

    /**
     * 某「周期 × 板块」是否已冻结。
     *
     * <p>判定<b>只看这条标记在不在</b>，不看快照行数——空榜（row_count=0）同样算已冻结。</p>
     *
     * @param periodType 周期码
     * @param periodKey  周期标识
     * @param rankType   榜单码
     * @return 标记数量，0 或 1
     */
    @Select("SELECT COUNT(*) FROM honor_ranking_snapshot_batch WHERE period_type = #{periodType} "
            + "AND period_key = #{periodKey} AND rank_type = #{rankType} AND is_deleted = 0")
    int countBatch(@Param("periodType") int periodType,
                   @Param("periodKey") String periodKey,
                   @Param("rankType") int rankType);

    /**
     * <b>物理</b>删除完成标记，供强制补跑时先清空再重写。
     *
     * <p>与 {@code HonorRankingSnapshotMapper.deletePeriod} 同样的理由：唯一键 {@code uk_batch}
     * 不含 {@code is_deleted}，逻辑删除会让旧行占着键，紧接着的重写就会撞唯一键失败。</p>
     *
     * @param periodType 周期码
     * @param periodKey  周期标识
     * @param rankType   榜单码
     * @return 删除行数
     */
    @Delete("DELETE FROM honor_ranking_snapshot_batch WHERE period_type = #{periodType} "
            + "AND period_key = #{periodKey} AND rank_type = #{rankType}")
    int deleteBatch(@Param("periodType") int periodType,
                    @Param("periodKey") String periodKey,
                    @Param("rankType") int rankType);
}
