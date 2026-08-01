package com.hengde.activity.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.activity.entity.ActivityAttendance;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 活动考勤/服务记录 Mapper。
 *
 * @author hengde
 */
public interface ActivityAttendanceMapper extends BaseMapper<ActivityAttendance> {

    /**
     * 统计某志愿者「已确认服务总时长」（分钟）——仅秘书部已确认(secretary_status=1)的记录计入，
     * 作为报名「已参加时长门槛」的口径（第 2 批 eligibility 用）。
     *
     * @param volunteerId 志愿者 id
     * @return 已确认服务总分钟数（无记录返回 0）
     */
    @Select("SELECT COALESCE(SUM(service_minutes), 0) FROM activity_attendance "
            + "WHERE volunteer_id = #{volunteerId} AND secretary_status = 1 AND is_deleted = 0")
    long sumConfirmedMinutes(@Param("volunteerId") Long volunteerId);

    /**
     * 按 id 取考勤行并<b>加排他行锁</b>（{@code FOR UPDATE}），必须在事务内调用。
     *
     * <p>用于「读出当前值 → 据此算增量 → 写回」这类读改写路径，当前唯一使用者是
     * {@link com.hengde.activity.service.ActivityChangeService} 的变更审核。为什么非要行锁：
     * 同一条考勤可以同时挂着多张待审的变更申请，它们的审核各自 CAS 自己的申请行，<b>彼此不冲突</b>，
     * 拦不住并发。而 InnoDB 默认 REPEATABLE READ 下普通 {@code selectById} 是快照读——两个审核线程
     * 会读到同一个旧积分值（如都读到 10），分别算出 +2 和 +5 写进账本，考勤快照却只会是 12 或 15，
     * 账实就此分离。{@code FOR UPDATE} 是当前读，会读到最新已提交值并阻塞对方，把两次审核串行化：
     * 后者看到的是 12，于是记 +3，账实一致。</p>
     *
     * @param id 考勤 id
     * @return 考勤行；不存在返回 null
     */
    @Select("SELECT * FROM activity_attendance WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    ActivityAttendance selectByIdForUpdate(@Param("id") Long id);
}
