package com.hengde.data.vo;

import com.hengde.donate.vo.DonateStatsVOs;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 数据汇总（V4 数据汇总批，Row 79 的九块）。
 *
 * <p><b>系统里没有的概念一律给 null，不给 0</b>（出参省略 null 字段，前端拿到的是「没有这个键」）：
 * 0 会被读成「一次都没有」，而真相是「这个系统根本不记这件事」。V3 收尾批的「修建书屋数」已有先例，
 * 这一批又多了相册的视频数与查看 / 下载人次。</p>
 *
 * @author hengde
 */
@Data
public class PlatformSummaryVO {

    private Platform platform;
    private Community community;
    private Enterprise enterprise;
    private Group group;
    private Album album;
    private Report report;
    @Schema(description = "微心愿 / 结对 / 捐书三块，与 GET /a/data/donation-summary 同一口径")
    private DonateStatsVOs.Summary donation;

    /** 平台数据（Row 79 第一块）。 */
    @Data
    public static class Platform {
        @Schema(description = "用户人数（含还没实名的游客）")
        private long users;
        private long registeredVolunteers;
        private long activityCount;
        private long totalServiceMinutes;
        private double totalServiceHours;
        private long participationCount;
        private long managerCount;
        private long squadCount;
    }

    /** 社区数据。 */
    @Data
    public static class Community {
        private long posts;
        @Schema(description = "发帖人数（志愿者作者去重）")
        private long posters;
        @Schema(description = "违规帖子数＝被审核驳回的 ∪ 被后台隐藏的；命中关键词还在排队的不算")
        private long violatingPosts;
        @Schema(description = "违规人次（发过违规帖的人去重）")
        private long violatingAuthors;
    }

    /** 爱心企业数据。 */
    @Data
    public static class Enterprise {
        private long enterprises;
        private long normalEnterprises;
        private long goods;
        @Schema(description = "已兑换（下单即占住积分与库存，含还没去取的）")
        private long orders;
        @Schema(description = "已发放（东西到手：现场核销 / 本人确认收货 / 系统自动确认）")
        private long delivered;
        @Schema(description = "企业收回的积分（兑换入账合计，不含后台手工调整）")
        private long creditedPoints;
        private long reviews;
    }

    /** 志愿小组数据。 */
    @Data
    public static class Group {
        private long groups;
        private long activeGroups;
        private long members;
        @Schema(description = "组员相互报名次数（同小组代报名）")
        private long proxyEnrollments;
        @Schema(description = "已解散的小组数——Row 79 写的是「被封小组数」，而系统里对应的动作是解散")
        private long dissolvedGroups;
    }

    /** 相册数据。 */
    @Data
    public static class Album {
        private long albums;
        private long photos;
        private long uploaders;
        @Schema(description = "上传人次（每次上传一批算一次）")
        private long uploadBatches;
        @Schema(description = "视频总数：相册只收照片，系统里没有这个概念——给 null 不给 0")
        private Long videos;
        @Schema(description = "查看人次：没有埋点——给 null 不给 0")
        private Long views;
        @Schema(description = "下载人数：没有埋点——给 null 不给 0")
        private Long downloaders;
        @Schema(description = "下载人次：没有埋点——给 null 不给 0")
        private Long downloads;
    }

    /** 举报与处罚数据。 */
    @Data
    public static class Report {
        @Schema(description = "举报数量（帖子 / 评论举报 + 私聊投诉；关键词自动开的工单不算「有人举报」）")
        private long reports;
        private long valid;
        private long invalid;
        @Schema(description = "举报人数（去重）")
        private long reporters;
        @Schema(description = "封号人数（账号被禁用的志愿者）")
        private long bannedAccounts;
        @Schema(description = "处罚数＝已通过的奖惩处罚单 + 社区禁言单")
        private long punishments;
    }
}
