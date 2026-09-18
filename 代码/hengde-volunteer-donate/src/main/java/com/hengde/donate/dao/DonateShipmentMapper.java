package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.donate.entity.DonateShipment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DonateShipment Mapper。
 *
 * @author hengde
 */
public interface DonateShipmentMapper extends BaseMapper<DonateShipment> {

    /** 完整物流轨迹（MEDIUMTEXT），实体上 {@code select = false}，只在看轨迹时单独读。 */
    @Select("SELECT track_json FROM donate_shipment WHERE id = #{id} AND is_deleted = 0")
    String selectTrackJson(@Param("id") Long id);

    /**
     * 待轮询的运单 id：未到终态、状态仍是「已寄出」、且距上次查询超过间隔。
     *
     * <p><b>走 {@code idx_track_pending(track_done, track_query_time)}</b>——扫描集合只含未到终态的运单，
     * 不随历史单量增长（与 V39 {@code idx_reminder_pending} 同形）。从没查过的（NULL）排在最前。</p>
     */
    @Select("SELECT id FROM donate_shipment WHERE track_done = 0 AND status = #{shipped} AND is_deleted = 0 "
            + "AND (track_query_time IS NULL OR track_query_time < #{before}) "
            + "AND (#{pushOn} = 0 OR subscribe_status IN (3, 4)) "
            + "ORDER BY track_query_time IS NOT NULL, track_query_time LIMIT #{limit}")
    List<Long> selectPollable(@Param("shipped") int shipped, @Param("before") LocalDateTime before,
                              @Param("pushOn") int pushOn, @Param("limit") int limit);

    // ================= 快递100 订阅推送（V56，物流推送批） =================

    /**
     * 待订阅的运单 id：还没订上、未到终态、仍是「已寄出」、距上次尝试超过重试间隔。
     * 走 {@code idx_subscribe_pending(subscribe_status, track_done, subscribe_attempt_time)}，从没试过的排在最前。
     */
    @Select("SELECT id FROM donate_shipment WHERE subscribe_status = 0 AND track_done = 0 AND status = #{shipped} "
            + "AND is_deleted = 0 AND (subscribe_attempt_time IS NULL OR subscribe_attempt_time < #{before}) "
            + "ORDER BY subscribe_attempt_time IS NOT NULL, subscribe_attempt_time, id LIMIT #{limit}")
    List<Long> selectSubscribable(@Param("shipped") int shipped, @Param("before") LocalDateTime before,
                                  @Param("limit") int limit);

    /**
     * <b>先占住、再发请求</b>：把「这一次由我来订」写进库，影响行数为 1 才去调快递100。
     *
     * <p>订阅是付费项，多实例 / 任务重叠时两边都去订就是两份钱。条件与扫描条件相同，
     * 后到的那一方等前者提交后重新判定，发现 {@code subscribe_attempt_time} 已是刚才，就是 0 行。
     * 顺序与 V39 活动提醒同形：宁可这一轮漏订（下一轮还会捞到），不可重订。</p>
     *
     * <p>salt 用 {@code COALESCE} 只在第一次写入：上一次其实订上了、只是应答丢了时，
     * 快递100 手里是旧 salt，换新的就再也验不过它的推送。</p>
     */
    @Update("UPDATE donate_shipment SET subscribe_attempt_time = #{now}, subscribe_attempts = subscribe_attempts + 1, "
            + "subscribe_salt = COALESCE(subscribe_salt, #{salt}) "
            + "WHERE id = #{id} AND subscribe_status = 0 AND track_done = 0 AND status = #{shipped} AND is_deleted = 0 "
            + "AND (subscribe_attempt_time IS NULL OR subscribe_attempt_time < #{before})")
    int claimSubscribe(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("before") LocalDateTime before,
                       @Param("salt") String salt, @Param("shipped") int shipped);

    @Select("SELECT subscribe_salt FROM donate_shipment WHERE id = #{id} AND is_deleted = 0")
    String selectSubscribeSalt(@Param("id") Long id);

    /** 订阅受理。只从「待订阅」迁出——推送先到、已经把它推成订阅中的，这里是 0 行，无妨。 */
    @Update("UPDATE donate_shipment SET subscribe_status = 1, subscribe_time = #{now}, subscribe_error = NULL, "
            + "update_time = #{now} WHERE id = #{id} AND subscribe_status = 0 AND is_deleted = 0")
    int markSubscribed(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 订阅失败：记原因；被明确拒绝的，或失败次数已到上限的，放弃（交还轮询）。
     * 「到没到上限」按库里的计数判，不按调用方读到的那个数——并发时那个数可能已经旧了。
     */
    @Update("UPDATE donate_shipment SET subscribe_error = #{error}, "
            + "subscribe_status = CASE WHEN #{permanent} = 1 OR subscribe_attempts >= #{maxAttempts} THEN 4 "
            + "ELSE subscribe_status END, update_time = #{now} "
            + "WHERE id = #{id} AND subscribe_status = 0 AND is_deleted = 0")
    int markSubscribeFailed(@Param("id") Long id, @Param("error") String error, @Param("permanent") int permanent,
                            @Param("maxAttempts") int maxAttempts, @Param("now") LocalDateTime now);

    /**
     * 写入一次推送带来的轨迹快照。
     *
     * <p>⚠️ <b>乱序保护写在 WHERE 里</b>：快递100 每次推的是全量轨迹，但投递会重试、会乱序——
     * 一条较早的推送晚到，会把快照盖回旧的样子。所以只在「新的最新节点不早于库里那一条」时才写；
     * 先读再判断拦不住两条推送同时进来。</p>
     *
     * <p>推送也刷新 {@code track_query_time}：它就是「最近一次拿到数据的时刻」，否则志愿者一打开页面又去按次付费查询。</p>
     */
    @Update("UPDATE donate_shipment SET track_state = #{state}, track_last_context = #{lastContext}, "
            + "track_last_time = #{lastTime}, track_json = #{json}, track_query_time = #{now}, push_time = #{now}, "
            + "track_done = CASE WHEN #{done} = 1 THEN 1 ELSE track_done END, update_time = #{now} "
            + "WHERE id = #{id} AND is_deleted = 0 "
            + "AND (track_last_time IS NULL OR (#{lastTime} IS NOT NULL AND track_last_time <= #{lastTime}))")
    int applyPushedTrack(@Param("id") Long id, @Param("state") Integer state, @Param("lastContext") String lastContext,
                         @Param("lastTime") LocalDateTime lastTime, @Param("json") String json,
                         @Param("now") LocalDateTime now, @Param("done") int done);

    /** 推送 status=polling / updateall：订阅确实在跑。应答丢了还停在「待订阅」的，借这条推送自愈。 */
    @Update("UPDATE donate_shipment SET subscribe_status = CASE WHEN subscribe_status = 0 THEN 1 ELSE subscribe_status END, "
            + "subscribe_time = COALESCE(subscribe_time, #{now}), push_time = #{now} WHERE id = #{id} AND is_deleted = 0")
    int markPushPolling(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 推送 status=shutdown：快递100 判定到终态，不会再推。任何状态都可迁入。 */
    @Update("UPDATE donate_shipment SET subscribe_status = 2, track_done = 1, push_time = #{now}, update_time = #{now} "
            + "WHERE id = #{id} AND is_deleted = 0")
    int markPushFinished(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 推送 status=abort：快递100 不再监控（如长时间无轨迹）——交还轮询。
     * 只从「待订阅 / 订阅中」迁入：已经推送结束的，一条迟到的 abort 不该把它拉回轮询集合。
     */
    @Update("UPDATE donate_shipment SET subscribe_status = CASE WHEN subscribe_status IN (0, 1) THEN 3 "
            + "ELSE subscribe_status END, subscribe_error = #{reason}, push_time = #{now} WHERE id = #{id} AND is_deleted = 0")
    int markPushAborted(@Param("id") Long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

    /** 后台重新订阅：只对「被中止 / 已放弃」且仍在途的运单，计数与原因清零。salt 保留（理由见 claim）。 */
    @Update("UPDATE donate_shipment SET subscribe_status = 0, subscribe_attempts = 0, subscribe_attempt_time = NULL, "
            + "subscribe_error = NULL, update_time = #{now} "
            + "WHERE id = #{id} AND subscribe_status IN (3, 4) AND track_done = 0 AND status = #{shipped} AND is_deleted = 0")
    int resetSubscribe(@Param("id") Long id, @Param("shipped") int shipped, @Param("now") LocalDateTime now);

    /**
     * 写入一次物流查询的快照。<b>只动 track_* 列</b>，不读实体再整行写回——
     * 同一时刻管理员可能正在扫码确认到货，整行写回会把状态盖回「已寄出」。
     */
    @Update("UPDATE donate_shipment SET track_state = #{state}, track_last_context = #{lastContext}, "
            + "track_last_time = #{lastTime}, track_json = #{json}, track_query_time = #{queryTime}, "
            + "track_done = CASE WHEN #{done} = 1 THEN 1 ELSE track_done END, update_time = #{queryTime} "
            + "WHERE id = #{id} AND is_deleted = 0")
    int updateTrack(@Param("id") Long id, @Param("state") Integer state, @Param("lastContext") String lastContext,
                    @Param("lastTime") LocalDateTime lastTime, @Param("json") String json,
                    @Param("queryTime") LocalDateTime queryTime, @Param("done") int done);

    /**
     * 某条业务记录下「还没处理完」的运单数（已寄出 / 已到货待核对），<b>当前读 + 共享锁</b>。
     *
     * <p>微心愿「实现」前用它：锁住这些运单行，与并发的扫码到货 / 核对串行化——否则刚判完「都核对完了」，
     * 另一台电脑就把一个包裹核对成合格，那几件物资会停在一个已实现的心愿下，永远没有下一步。</p>
     */
    @Select("SELECT COUNT(*) FROM donate_shipment WHERE biz_type = #{bizType} AND biz_id = #{bizId} "
            + "AND status IN (#{shipped}, #{arrived}) AND is_deleted = 0 FOR SHARE")
    long countUnsettledForShare(@Param("bizType") int bizType, @Param("bizId") Long bizId,
                                @Param("shipped") int shipped, @Param("arrived") int arrived);

    /** 补录物资用：运单头的当前读（调用方已持有该行 X 锁，见 {@code DonateShipmentService.addItem}）。 */
    @Select("SELECT id, biz_type, biz_id, donor_volunteer_id, status FROM donate_shipment "
            + "WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    DonateShipment selectHeadForUpdate(@Param("id") Long id);

    /** 查询失败也要记下查询时间，否则一个查不到的单号会在每一轮都排在队首，把别的单挤掉。 */
    @Update("UPDATE donate_shipment SET track_query_time = #{queryTime}, "
            + "track_done = CASE WHEN #{done} = 1 THEN 1 ELSE track_done END WHERE id = #{id} AND is_deleted = 0")
    int touchTrackQuery(@Param("id") Long id, @Param("queryTime") LocalDateTime queryTime, @Param("done") int done);
}
