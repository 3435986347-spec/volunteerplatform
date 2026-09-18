package com.hengde.honor.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 奖惩的数据汇总口径（V4 数据汇总批，Row 79 举报数据里的「处罚数」）。
 *
 * <p><b>只数「已通过（终审）」的处罚单</b>：待审与被驳回的单在 V40 之后不产生任何效力
 * （志愿者看不到、积分没入账、处置没生效），把它们算进「处罚数」等于把没发生的事报成发生了。</p>
 *
 * @author hengde
 */
@Service
public class HonorStatsService {

    private JdbcTemplate jdbc;

    @Autowired
    public void setJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 已通过的处罚单数（type=2 处罚、review_status=1 已通过）。 */
    public long countApprovedPunishments() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM honor_reward_punish WHERE is_deleted = 0 "
                + "AND type = 2 AND review_status = 1", Long.class);
        return n == null ? 0L : n;
    }
}
