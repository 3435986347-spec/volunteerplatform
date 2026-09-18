package com.hengde.donate.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hengde.activity.vo.RankingRowView;
import com.hengde.donate.entity.DonateWishClaim;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DonateWishClaim Mapper。
 *
 * @author hengde
 */
public interface DonateWishClaimMapper extends BaseMapper<DonateWishClaim> {

    /**
     * 微心愿排行（Row 18 第四板块）：区间内<b>实现</b>的心愿数，按人排序。
     *
     * <p>口径（《协会待确认清单-v3》⑯ 默认）：按「已实现」算、按实现时间切周期——认领可以取消，
     * 按认领算会让榜单反复抖动，而往期名次一旦冻结就不该再变。
     * 区间<b>左闭右开</b>，null 表示不限（与 {@code ActivityRankingQueryService} 同一约定，
     * 那边的月榜 / 年榜换算由 honor 的 {@code RankingPeriod} 统一给出）。
     * 并列按 volunteerId 升序定序——快照重跑名次不能变。</p>
     */
    @Select("<script>SELECT volunteer_id AS volunteerId, COUNT(*) AS metricValue FROM donate_wish_claim "
            + "WHERE status = #{realized} AND is_deleted = 0 "
            + "<if test='from != null'> AND realize_time &gt;= #{from}</if>"
            + "<if test='to != null'> AND realize_time &lt; #{to}</if>"
            + " GROUP BY volunteer_id ORDER BY metricValue DESC, volunteer_id ASC LIMIT #{limit}</script>")
    List<RankingRowView> topRealized(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                     @Param("limit") int limit, @Param("realized") int realized);

    /**
     * 锁住某个心愿当前的「活」认领（认领中或已实现），<b>走 {@code uk_active_wish} 唯一键</b>——只锁这一行，
     * 不像按 {@code idx_wish} 普通索引那样连带锁一段范围。状态与认领人由调用方判断。
     *
     * <p>登记寄出 / 取消 / 撤销 / 实现四个动作都以它开头，于是同一个心愿上的这几件事一定一个在前一个在后。</p>
     */
    @Select("SELECT * FROM donate_wish_claim WHERE active_wish_key = #{wishId} AND is_deleted = 0 FOR UPDATE")
    DonateWishClaim selectLiveByWishForUpdate(@Param("wishId") Long wishId);

    /** 后台补录物资前的当前读（共享锁），见 {@code DonateShipmentService.addItem}。 */
    @Select("SELECT * FROM donate_wish_claim WHERE id = #{id} AND is_deleted = 0 FOR SHARE")
    DonateWishClaim selectByIdForShare(@Param("id") Long id);
}
