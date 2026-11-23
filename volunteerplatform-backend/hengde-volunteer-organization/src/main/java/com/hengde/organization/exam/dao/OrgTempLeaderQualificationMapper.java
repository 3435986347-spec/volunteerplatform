package com.hengde.organization.exam.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.organization.exam.entity.OrgTempLeaderQualification;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** 活动临时负责人资格（V75）。「现在是否有效」一律按时间现算：没撤销、且不设期限或还没到期。 */
public interface OrgTempLeaderQualificationMapper extends BaseMapper<OrgTempLeaderQualification> {

    /** SQL 片段：这一行现在有效。 */
    String ACTIVE = "revoked_time IS NULL AND (expire_time IS NULL OR expire_time > #{now})";

    /**
     * 把一条已经到期、还占着唯一键的资格以「资格到期」收尾（撤销人为空），腾出 {@code uk_active_qualification} 给新资格。
     *
     * <p>⚠️ <b>按主键收尾，不按 {@code volunteer_id} 范围 UPDATE</b>：对一个还没有任何资格行的人做范围 UPDATE，
     * 会在 {@code idx_volunteer} 上留下间隙锁，两个 id 相邻的人同时及格时各自持着同一段间隙、再去 INSERT 取插入意向锁，互等成死锁
     * （压测 {@code ExamConcurrencyTest.examChurn_invariantsHold} 三次三中）。要收尾哪几行由调用方先普通读找出来——
     * 这个人的交卷 / 阅卷 / 撤销持同一把按人的锁，普通读与这条 UPDATE 之间没有别人改他的资格。</p>
     */
    @Update("UPDATE org_temp_leader_qualification SET revoked_time = #{now}, revoke_reason = #{reason} "
            + "WHERE id = #{id} AND revoked_time IS NULL AND expire_time IS NOT NULL AND expire_time <= #{now}")
    int closeExpiredById(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("reason") String reason);

    /** 撤销：条件写在 WHERE 里（还没撤销、还没到期），影响行数为 1 才算撤成。 */
    @Update("UPDATE org_temp_leader_qualification SET revoked_by = #{adminId}, revoked_time = #{now}, revoke_reason = #{reason} "
            + "WHERE id = #{id} AND " + ACTIVE)
    int revoke(@Param("id") Long id, @Param("adminId") Long adminId, @Param("reason") String reason, @Param("now") LocalDateTime now);
}
