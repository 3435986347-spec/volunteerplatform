package com.hengde.organization.biz.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 志愿小组的数据汇总口径（V4 数据汇总批，Row 79「志愿小组数据（小组总数、组员数、组员相互报名次数、被封小组数）」）。
 *
 * <p>⚠️ <b>系统里没有「封小组」这个动作</b>：小组只有「解散」（组长或后台解散，写 dissolve_time）。
 * 这里给的是<b>已解散的小组数</b>，出参字段名与文档都写明这一点——把它叫成「被封」会让看数的人以为协会封过谁。</p>
 *
 * <p>「组员相互报名次数」是<b>代报名</b>（同小组成员互相报名，`enrollment.proxy_by_volunteer_id` 非空），口径在 activity 域，这里不重复实现。</p>
 *
 * @author hengde
 */
@Service
public class GroupStatsService {

    private JdbcTemplate jdbc;

    @Autowired
    public void setJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 小组总数（没删的，含待审核与已解散；下面两项各自再筛）。 */
    public long countGroups() {
        return one("SELECT COUNT(*) FROM volunteer_group WHERE is_deleted = 0");
    }

    /** 生效中的小组数（审核通过、没解散——状态 1；解散会把状态改成 3）。 */
    public long countActiveGroups() {
        return one("SELECT COUNT(*) FROM volunteer_group WHERE is_deleted = 0 AND status = 1");
    }

    /** 组员数（已加入的成员行，去重到人）。 */
    public long countMembers() {
        return one("SELECT COUNT(DISTINCT volunteer_id) FROM volunteer_group_member WHERE is_deleted = 0 AND status = 1");
    }

    /** 已解散的小组数（Row 79 写的是「被封小组数」，系统里对应的动作是解散：状态 3）。 */
    public long countDissolvedGroups() {
        return one("SELECT COUNT(*) FROM volunteer_group WHERE is_deleted = 0 AND status = 3");
    }

    private long one(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }
}
