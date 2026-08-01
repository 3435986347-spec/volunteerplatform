package com.hengde.activity.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.activity.entity.PointRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 积分流水 Mapper。
 *
 * <p>余额一律由 {@code SUM(change_amount)} 求得，表中不冗余存储余额列——写入需与调用方业务变更同事务，
 * 而锁只能落在该事务内，无法保证「读余额→写余额」的跨事务可见性（详见 V24 迁移脚本注释）。
 * 纯 SUM 是自校正的，且让写入退化为无状态追加。</p>
 *
 * @author hengde
 */
public interface PointRecordMapper extends BaseMapper<PointRecord> {

    /**
     * 当前积分余额（{@code SUM(change_amount)} 现算）。
     *
     * <p>入账<b>不以余额参与决策</b>，这里只是把结果回给调用方；唯一读余额再决策的是手工扣分的非负校验。</p>
     *
     * @param volunteerId 志愿者 id
     * @return 余额；无流水返回 0
     */
    @Select("SELECT COALESCE(SUM(change_amount), 0) FROM point_record "
            + "WHERE volunteer_id = #{volunteerId} AND is_deleted = 0")
    int sumBalance(@Param("volunteerId") Long volunteerId);

    /**
     * 批量取多人余额，供「志愿者管理」列表等场景避免 N+1。
     *
     * @param volunteerIds 志愿者 id 集合（调用方保证非空）
     * @return 每人一行 {volunteerId, balance}；无流水者不出现在结果中，调用方按缺省 0 处理
     */
    @Select("<script>"
            + "SELECT volunteer_id AS volunteerId, COALESCE(SUM(change_amount), 0) AS balance "
            + "FROM point_record WHERE is_deleted = 0 AND volunteer_id IN "
            + "<foreach collection='volunteerIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY volunteer_id"
            + "</script>")
    List<PointBalanceRow> selectBalances(@Param("volunteerIds") Collection<Long> volunteerIds);

    /**
     * 按来源单据取流水（幂等判定用的<b>快照读</b>，走常规事务可见性）。
     *
     * @param sourceType 来源类型
     * @param sourceId   来源单据 id
     * @return 流水；无则 null
     */
    @Select("SELECT * FROM point_record WHERE source_type = #{sourceType} AND source_id = #{sourceId} "
            + "AND is_deleted = 0 LIMIT 1")
    PointRecord selectBySource(@Param("sourceType") int sourceType, @Param("sourceId") Long sourceId);

    /**
     * 按幂等键取流水（快照读）。
     *
     * @param requestId 幂等键
     * @return 流水；无则 null
     */
    @Select("SELECT * FROM point_record WHERE request_id = #{requestId} AND is_deleted = 0 LIMIT 1")
    PointRecord selectByRequestId(@Param("requestId") String requestId);

    /**
     * 同上两者的<b>当前读</b>版本（{@code FOR SHARE}），专供插入抛 {@code DuplicateKeyException} 后的复核。
     *
     * <p>为什么这里不能用快照读：唯一键冲突意味着另一事务<b>已提交</b>了同键行（未提交时 InnoDB 会让本次
     * INSERT 阻塞等待，而不是立刻报重复键）。但在 REPEATABLE READ 下，本事务的快照可能早于对方提交，
     * 普通 SELECT 会读不到那一行，复核就会误判成「查无此条」。当前读绕过快照，始终读最新已提交版本。</p>
     *
     * <p><b>为什么是 {@code FOR SHARE} 而不是 {@code FOR UPDATE}——这里踩过一次死锁</b>：
     * InnoDB 在 INSERT 报重复键时，会先给冲突的索引记录加上<b>共享锁（S）</b>。若有两个及以上事务
     * 同时撞同一个键，它们会各自持有该行的 S 锁；此时谁再请求 {@code FOR UPDATE} 的排他锁（X），
     * 就要等对方释放 S 锁，而对方也在等自己——典型的锁升级死锁，MySQL 会直接判其中一方
     * {@code ERROR 1213 Deadlock}。本方法只是<b>读出来比对载荷、不改这一行</b>，共享锁足矣：
     * S 锁与已持有的 S 锁相容，多个 loser 可以同时复核并各自正常返回。</p>
     *
     * <p>注意别把这条经验推广到 {@code ActivityAttendanceMapper.selectByIdForUpdate}——那里是真正的
     * 「读出来→改→写回」，必须排他锁。判断标准是<b>读完之后改不改这一行</b>。</p>
     */
    @Select("SELECT * FROM point_record WHERE source_type = #{sourceType} AND source_id = #{sourceId} "
            + "AND is_deleted = 0 LIMIT 1 FOR SHARE")
    PointRecord selectBySourceForShare(@Param("sourceType") int sourceType, @Param("sourceId") Long sourceId);

    /** @see #selectBySourceForShare */
    @Select("SELECT * FROM point_record WHERE request_id = #{requestId} AND is_deleted = 0 LIMIT 1 FOR SHARE")
    PointRecord selectByRequestIdForShare(@Param("requestId") String requestId);

    /**
     * 按来源类型汇总某人的积分，供总览按「获得 / 消费」分类统计。
     *
     * <p>不在 SQL 里写死哪些来源算消费——分类口径由 {@code PointSourceType.isConsumption} 单一持有，
     * 这里只按来源分组返回（至多几行），由服务层归类，避免 SQL 与常量两处各存一份定义而漂移。</p>
     *
     * @param volunteerId 志愿者 id
     * @return 每个来源类型一行 {sourceType, total}；无流水返回空列表
     */
    @Select("SELECT source_type AS sourceType, COALESCE(SUM(change_amount), 0) AS total "
            + "FROM point_record WHERE volunteer_id = #{volunteerId} AND is_deleted = 0 "
            + "GROUP BY source_type")
    List<PointSourceSumRow> selectSumsBySourceType(@Param("volunteerId") Long volunteerId);

    /** 按来源类型汇总的行结果。 */
    class PointSourceSumRow {
        private Integer sourceType;
        private Integer total;

        public Integer getSourceType() {
            return sourceType;
        }

        public void setSourceType(Integer sourceType) {
            this.sourceType = sourceType;
        }

        public Integer getTotal() {
            return total;
        }

        public void setTotal(Integer total) {
            this.total = total;
        }
    }

    /** 批量余额查询的行结果。 */
    class PointBalanceRow {
        private Long volunteerId;
        private Integer balance;

        public Long getVolunteerId() {
            return volunteerId;
        }

        public void setVolunteerId(Long volunteerId) {
            this.volunteerId = volunteerId;
        }

        public Integer getBalance() {
            return balance;
        }

        public void setBalance(Integer balance) {
            this.balance = balance;
        }
    }
}
