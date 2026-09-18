package com.hengde.social.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 社区的数据汇总口径（V4 数据汇总批，Row 79「社区数据（帖子总数、发帖人数、违规帖子数、违规人次）」+「举报数据」）。
 *
 * <p><b>口径收在这里，不外泄给 data 去拼条件</b>（与排行榜、看板同一条纪律）：
 * 「什么算一条帖子」「什么算违规」这些话只能由社区域说，散开就会在两个页面上显示两个数。</p>
 *
 * <p><b>违规帖子＝被审核驳回的 ∪ 被后台隐藏的</b>：这两样都是「人工判过它不该在」；
 * 命中关键词还没审完的<b>不算</b>——那只是排队，审完可能是通过。<b>违规人次按作者去重</b>（Row 79 原文是「违规人次」，
 * 一个人发十条违规帖是十条帖子、一个人）。</p>
 *
 * @author hengde
 */
@Service
public class SocialStatsService {

    private JdbcTemplate jdbc;

    @Autowired
    public void setJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 帖子总数（没删的，含官方帖与企业帖）。 */
    public long countPosts() {
        return one("SELECT COUNT(*) FROM social_post WHERE is_deleted = 0");
    }

    /** 发帖人数（志愿者作者去重；官方帖与企业帖不是「人」）。 */
    public long countPosters() {
        return one("SELECT COUNT(DISTINCT author_id) FROM social_post WHERE is_deleted = 0 AND author_type = 1");
    }

    /** 违规帖子数。 */
    public long countViolatingPosts() {
        return one("SELECT COUNT(*) FROM social_post WHERE is_deleted = 0 AND (review_status = 2 OR admin_hidden = 1)");
    }

    /** 违规人次（发过违规帖的志愿者去重）。 */
    public long countViolatingAuthors() {
        return one("SELECT COUNT(DISTINCT author_id) FROM social_post WHERE is_deleted = 0 AND author_type = 1 "
                + "AND (review_status = 2 OR admin_hidden = 1)");
    }

    /** 举报数量（帖子 / 评论举报 + 私聊工单里的用户投诉；关键词自动开的工单不算「有人举报」）。 */
    public long countReports() {
        return one("SELECT COUNT(*) FROM social_report")
                + one("SELECT COUNT(*) FROM social_chat_report WHERE source = 1");
    }

    /** 核实违规（举报成立）。 */
    public long countReportsValid() {
        return one("SELECT COUNT(*) FROM social_report WHERE status = 1")
                + one("SELECT COUNT(*) FROM social_chat_report WHERE source = 1 AND status = 1");
    }

    /** 核实无违规（举报不成立）。 */
    public long countReportsInvalid() {
        return one("SELECT COUNT(*) FROM social_report WHERE status = 2")
                + one("SELECT COUNT(*) FROM social_chat_report WHERE source = 1 AND status = 2");
    }

    /** 举报人数（去重；同一个人举报十次算一个人）。 */
    public long countReporters() {
        return one("SELECT COUNT(*) FROM (SELECT reporter_id FROM social_report "
                + "UNION SELECT reporter_id FROM social_chat_report WHERE source = 1 AND reporter_id IS NOT NULL) t");
    }

    /** 社区禁言单数（Row 79「处罚数」里社区这一侧的部分；奖惩域的处罚另计）。 */
    public long countBans() {
        return one("SELECT COUNT(*) FROM social_ban");
    }

    private long one(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }
}
