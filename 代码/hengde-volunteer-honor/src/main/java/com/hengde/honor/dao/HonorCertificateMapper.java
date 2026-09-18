package com.hengde.honor.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.honor.entity.HonorCertificate;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 证书 Mapper。
 *
 * @author hengde
 */
public interface HonorCertificateMapper extends BaseMapper<HonorCertificate> {

    /**
     * 按业务唯一键取证书——<b>无视逻辑删除</b>。
     *
     * <p>必须绕开 {@code @TableLogic}：软删口径取「恢复原记录」，自动创建时要能命中
     * <b>已软删</b>的那一行并把它复活。走 MP 的常规查询会自动追加 {@code is_deleted = 0}，
     * 于是永远命中不到，转而走插入分支，最后撞 {@code uk_slot_cert} 报错。</p>
     */
    @Select("SELECT * FROM honor_certificate WHERE type = #{type} AND volunteer_id = #{volunteerId} "
            + "AND slot_id = #{slotId} LIMIT 1")
    HonorCertificate selectBySlotIncludeDeleted(@Param("type") int type,
                                               @Param("volunteerId") Long volunteerId,
                                               @Param("slotId") Long slotId);

    /**
     * 与上一条相同，但用 <b>{@code FOR SHARE} 当前读</b>——专供「插入撞唯一键之后取回赢家」。
     *
     * <p><b>为什么那里不能用普通快照读</b>：项目跑在 RR 下，而 {@code createForSlot} 在
     * 「事件监听器」与「补偿扫描」两条路径上都处在事务里，快照在事务的第一次 SELECT 就定死了。
     * 赢家是在那之后才提交的，普通再读<b>看不见它</b>，于是 {@code winner == null} 成立，
     * 代码会把一次 {@code uk_slot_cert} 冲突误判成 {@code uk_cert_no} 冲突——
     * 接着换 5 个号撞 5 次同一个键，日志指向编号生成（那里毫无问题），
     * 最后计入 {@code failed}，让「补完了」的判据反过来<b>误报没补完</b>。
     * 数据始终是对的（唯一键仍在挡重复），错的是报告与诊断方向。</p>
     *
     * <p><b>为什么是 {@code FOR SHARE} 而不是 {@code FOR UPDATE}</b>：报重复键时 InnoDB
     * <b>已经给冲突行加了 S 锁</b>，多个 loser 再各自把 S 升级为 X 会互等成死锁——
     * 这正是积分账本 {@code PointService} 踩过并写进 {@code PointRecordLockOrderTest} 的那一条。
     * 这里只需要「看见赢家」，S 锁本就持有，取 {@code FOR SHARE} 不产生任何升级；
     * 而 {@code FOR UPDATE} 会让<b>每个</b> loser 都必然升级，两个 loser 即成环——
     * 两者是量级差别，不是「风险相当选轻的」。
     * 调用方随后的 {@code restoreIfDeleted} 是本分支上唯一可能引入升级的地方
     * （UPDATE 仍要取 X，「它是当前读」免不掉这一点），但它在那里实际不可达，
     * 理由见 {@code CertificateService#createForSlot} 的注释——<b>那条不可达性是本结论的前提</b>。</p>
     *
     * <p><b>一点无害的副作用，记下来免得日后查锁等待时对不上</b>：真撞 {@code uk_cert_no} 时
     * 本查询取不到行，RR 下唯一索引上的加锁读会在那个位置留一个 gap lock，
     * 比普通读多出一点点阻塞面。事务很短，且 {@code PointService} 的同款分支本来就是这个行为，
     * 两处一致即可。</p>
     */
    @Select("SELECT * FROM honor_certificate WHERE type = #{type} AND volunteer_id = #{volunteerId} "
            + "AND slot_id = #{slotId} LIMIT 1 FOR SHARE")
    HonorCertificate selectBySlotForShare(@Param("type") int type,
                                          @Param("volunteerId") Long volunteerId,
                                          @Param("slotId") Long slotId);

    /** 按 id 取证书——<b>无视逻辑删除</b>，供「撤销删除」入口读取已软删的行。 */
    @Select("SELECT * FROM honor_certificate WHERE id = #{id}")
    HonorCertificate selectByIdIncludeDeleted(@Param("id") Long id);

    /**
     * 按业务来源键取证书（<b>含已软删</b>），用于捐赠证书这类没有场次的类型。
     *
     * <p>{@code uk_slot_cert} 对它们不起作用（slot_id 为 NULL，MySQL 视多个 NULL 互不相同），
     * 幂等靠 V54 的 {@code uk_cert_biz_ref}；这条查询走的就是那个键。</p>
     */
    @Select("SELECT * FROM honor_certificate WHERE active_biz_ref = CONCAT(#{type}, ':', #{bizRef})")
    HonorCertificate selectByBizRefIncludeDeleted(@Param("type") int type, @Param("bizRef") String bizRef);

    /**
     * 同上，但<b>当前读</b>（共享锁）——撞唯一键之后取回赢家那一行必须用它。
     *
     * <p>RR 下快照在事务第一次 SELECT 就定死，普通读看不见赢家后来提交的行，
     * 会把 {@code uk_cert_biz_ref} 冲突误判成 {@code uk_cert_no} 冲突（换号重试到耗尽）。
     * 用 {@code FOR SHARE} 而非 {@code FOR UPDATE}：报重复键时 InnoDB 已给冲突行加了 S 锁，
     * 多个 loser 再抢 X 会互等成死锁（{@code PointRecordLockOrderTest} 已把这条钉死）。</p>
     */
    @Select("SELECT * FROM honor_certificate WHERE active_biz_ref = CONCAT(#{type}, ':', #{bizRef}) FOR SHARE")
    HonorCertificate selectByBizRefForShare(@Param("type") int type, @Param("bizRef") String bizRef);

    /**
     * 批量取「已存在证书」的 {@code volunteerId:slotId} 组合键，<b>含已软删</b>。
     *
     * <p>给补偿扫描用：它每轮要过一遍全部已确认考勤，逐行 select 会变成 N 次查询，
     * 表一大就慢得没法按小时跑。这里一批一次查询，job 侧做差集。</p>
     *
     * <p><b>必须含已软删</b>：软删行仍占 {@code uk_slot_cert}，
     * 把它当成「不存在」会让补偿任务反复尝试插入并撞键。</p>
     */
    @Select("""
            <script>
            SELECT CONCAT(volunteer_id, ':', slot_id) FROM honor_certificate
            WHERE type = #{type} AND slot_id IN
            <foreach collection="slotIds" item="s" open="(" separator="," close=")">#{s}</foreach>
            </script>
            """)
    java.util.List<String> selectExistingKeys(@Param("type") int type,
                                              @Param("slotIds") java.util.Collection<Long> slotIds);

    /**
     * 后台汇总查询，<b>可包含已软删的行</b>。
     *
     * <p><b>为什么必须有这条</b>：MP 的 {@code @TableLogic} 会给所有常规查询自动追加
     * {@code is_deleted = 0}，于是后台列表看不到自己刚删掉的证书——而
     * {@code POST /a/honor/certificates/{id}/restore} 需要那个 id。
     * 只有删除入口没有找回入口，「撤销删除」就是个用不上的接口。</p>
     *
     * @param includeDeleted true=连已删的一起返回（列表上按 isDeleted 区分展示）
     */
    @Select("""
            <script>
            SELECT * FROM honor_certificate
            <where>
              <if test="!includeDeleted"> is_deleted = 0 </if>
              <if test="volunteerId != null"> AND volunteer_id = #{volunteerId} </if>
              <if test="activityId != null"> AND activity_id = #{activityId} </if>
            </where>
            ORDER BY id DESC
            </script>
            """)
    com.baomidou.mybatisplus.core.metadata.IPage<HonorCertificate> selectAdminPage(
            com.baomidou.mybatisplus.core.metadata.IPage<HonorCertificate> page,
            @Param("volunteerId") Long volunteerId,
            @Param("activityId") Long activityId,
            @Param("includeDeleted") boolean includeDeleted);

    /**
     * 懒渲染回填 {@code file_key} 的 CAS。
     *
     * <p>{@code WHERE file_key IS NULL} 保证并发下只有第一个渲染完成的写得进去，
     * 后到的拿到 0 行、转而复用已有文件，<b>不会覆盖</b>。</p>
     *
     * @return 影响行数：1=本次回填成功，0=已有他人回填
     */
    @Update("UPDATE honor_certificate SET file_key = #{fileKey}, generate_time = NOW(), update_time = NOW() "
            + "WHERE id = #{id} AND file_key IS NULL AND is_deleted = 0")
    int fillFileKeyIfAbsent(@Param("id") Long id, @Param("fileKey") String fileKey);

    /**
     * 下载次数 +1。
     *
     * <p>用 SQL 原地自增而非「读出来 +1 再写回」：后者在并发下会丢计数。</p>
     */
    @Update("UPDATE honor_certificate SET download_count = download_count + 1, update_time = NOW() "
            + "WHERE id = #{id} AND is_deleted = 0")
    int incrementDownloadCount(@Param("id") Long id);

    /**
     * 下载次数 −1（补偿）。
     *
     * <p>计数被用作「签发前的最后一道闸」，必须在签名之前执行；但签名失败时用户什么也没拿到，
     * 那一次不该算下载。此处补偿回退。{@code download_count > 0} 防止减成负数。</p>
     */
    @Update("UPDATE honor_certificate SET download_count = download_count - 1, update_time = NOW() "
            + "WHERE id = #{id} AND download_count > 0")
    int decrementDownloadCount(@Param("id") Long id);

    /**
     * 软删（CAS：仅未删的行可删）。
     *
     * <p>不用 MP 的 {@code deleteById}——那只会写 {@code is_deleted}，
     * 而 Row 36 F 列要求留痕删除人/时间/原因。</p>
     *
     * @return 影响行数；0 = 不存在或已删
     */
    @Update("UPDATE honor_certificate SET is_deleted = 1, deleted_by = #{operatorId}, deleted_time = NOW(), "
            + "deleted_reason = #{reason}, update_time = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDelete(@Param("id") Long id, @Param("operatorId") Long operatorId, @Param("reason") String reason);

    /**
     * 撤销软删（CAS：仅已删的行可恢复），清空删除留痕。
     *
     * @return 影响行数；0 = 不存在或未删
     */
    @Update("UPDATE honor_certificate SET is_deleted = 0, deleted_by = NULL, deleted_time = NULL, "
            + "deleted_reason = NULL, update_time = NOW() WHERE id = #{id} AND is_deleted = 1")
    int restore(@Param("id") Long id);
}
