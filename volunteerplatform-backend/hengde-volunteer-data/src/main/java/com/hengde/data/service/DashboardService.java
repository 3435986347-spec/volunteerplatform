package com.hengde.data.service;

import com.hengde.activity.album.service.AlbumStatsService;
import com.hengde.activity.service.ActivityStatsService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.data.vo.DashboardVO;
import com.hengde.data.vo.PlatformSummaryVO;
import com.hengde.enterprise.service.EnterpriseStatsService;
import com.hengde.honor.service.HonorStatsService;
import com.hengde.organization.biz.service.GroupStatsService;
import com.hengde.social.service.SocialStatsService;
import com.hengde.donate.service.DonateStatsService;
import com.hengde.donate.vo.DonateStatsVOs;
import com.hengde.organization.biz.service.SquadQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 数据看板聚合服务：跨域只读拼装头部统计（注册人数/场次/时长/参与人次/管理团队/分队）。
 *
 * <p>各项指标的<b>领域语义</b>（哪些算注册、哪些算一场活动、时长口径=秘书已确认……）都收在各域只读 service 里，
 * 本服务只负责调用与组装，不直接捅外域表。志愿者端首页看板与管理端概览共用同一组数字。</p>
 *
 * @author hengde
 */
@Service
public class DashboardService {

    private VolunteerQueryService volunteerQueryService;
    private ActivityStatsService activityStatsService;
    private SquadQueryService squadQueryService;
    private DonateStatsService donateStatsService;
    private SocialStatsService socialStatsService;
    private EnterpriseStatsService enterpriseStatsService;
    private GroupStatsService groupStatsService;
    private AlbumStatsService albumStatsService;
    private HonorStatsService honorStatsService;

    @Autowired
    public void setSocialStatsService(SocialStatsService socialStatsService) {
        this.socialStatsService = socialStatsService;
    }

    @Autowired
    public void setEnterpriseStatsService(EnterpriseStatsService enterpriseStatsService) {
        this.enterpriseStatsService = enterpriseStatsService;
    }

    @Autowired
    public void setGroupStatsService(GroupStatsService groupStatsService) {
        this.groupStatsService = groupStatsService;
    }

    @Autowired
    public void setAlbumStatsService(AlbumStatsService albumStatsService) {
        this.albumStatsService = albumStatsService;
    }

    @Autowired
    public void setHonorStatsService(HonorStatsService honorStatsService) {
        this.honorStatsService = honorStatsService;
    }

    @Autowired
    public void setDonateStatsService(DonateStatsService donateStatsService) {
        this.donateStatsService = donateStatsService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setActivityStatsService(ActivityStatsService activityStatsService) {
        this.activityStatsService = activityStatsService;
    }

    @Autowired
    public void setSquadQueryService(SquadQueryService squadQueryService) {
        this.squadQueryService = squadQueryService;
    }

    /** 头部统计聚合。 */
    public DashboardVO overview() {
        DashboardVO vo = new DashboardVO();
        vo.setRegisteredVolunteers(volunteerQueryService.countRegistered());
        vo.setManagerCount(volunteerQueryService.countManagers());
        vo.setActivityCount(activityStatsService.countActivities());
        long minutes = activityStatsService.sumConfirmedServiceMinutes();
        vo.setTotalServiceMinutes(minutes);
        vo.setTotalServiceHours(Math.round(minutes * 10.0 / 60.0) / 10.0);
        vo.setParticipationCount(activityStatsService.countParticipations());
        vo.setSquadCount(squadQueryService.count());
        return vo;
    }

    /**
     * 捐赠数据汇总（Row 79 的微心愿 / 结对 / 捐书活动三块，V3 收尾批）。口径在 donate 的 {@code DonateStatsMapper}，这里只转发——
     * 与头部统计分开一个接口：它含金额，而头部统计志愿者端首页也在用。
     */
    public DonateStatsVOs.Summary donationSummary() {
        return donateStatsService.summary();
    }

    /**
     * 数据汇总（Row 79 的九块，V4 数据汇总批）。每个数字的口径都收在 owner 域的只读 service 里，这里只组装。
     *
     * <p><b>系统里没有的概念给 null 不给 0</b>（相册的视频数与查看 / 下载人次、捐书的修建书屋数）：
     * 0 会被读成「一次都没有」，而真相是「这个系统根本不记这件事」。</p>
     */
    public PlatformSummaryVO summary() {
        PlatformSummaryVO vo = new PlatformSummaryVO();

        DashboardVO head = overview();
        PlatformSummaryVO.Platform platform = new PlatformSummaryVO.Platform();
        platform.setUsers(volunteerQueryService.countAllUsers());
        platform.setRegisteredVolunteers(head.getRegisteredVolunteers());
        platform.setActivityCount(head.getActivityCount());
        platform.setTotalServiceMinutes(head.getTotalServiceMinutes());
        platform.setTotalServiceHours(head.getTotalServiceHours());
        platform.setParticipationCount(head.getParticipationCount());
        platform.setManagerCount(head.getManagerCount());
        platform.setSquadCount(head.getSquadCount());
        vo.setPlatform(platform);

        PlatformSummaryVO.Community community = new PlatformSummaryVO.Community();
        community.setPosts(socialStatsService.countPosts());
        community.setPosters(socialStatsService.countPosters());
        community.setViolatingPosts(socialStatsService.countViolatingPosts());
        community.setViolatingAuthors(socialStatsService.countViolatingAuthors());
        vo.setCommunity(community);

        PlatformSummaryVO.Enterprise enterprise = new PlatformSummaryVO.Enterprise();
        enterprise.setEnterprises(enterpriseStatsService.countEnterprises());
        enterprise.setNormalEnterprises(enterpriseStatsService.countNormalEnterprises());
        enterprise.setGoods(enterpriseStatsService.countSponsorGoods());
        enterprise.setOrders(enterpriseStatsService.countSponsorOrders());
        enterprise.setDelivered(enterpriseStatsService.countSponsorOrdersPicked());
        enterprise.setCreditedPoints(enterpriseStatsService.sumCreditedPoints());
        enterprise.setReviews(enterpriseStatsService.countReviews());
        vo.setEnterprise(enterprise);

        PlatformSummaryVO.Group group = new PlatformSummaryVO.Group();
        group.setGroups(groupStatsService.countGroups());
        group.setActiveGroups(groupStatsService.countActiveGroups());
        group.setMembers(groupStatsService.countMembers());
        group.setProxyEnrollments(activityStatsService.countProxyEnrollments());
        group.setDissolvedGroups(groupStatsService.countDissolvedGroups());
        vo.setGroup(group);

        PlatformSummaryVO.Album album = new PlatformSummaryVO.Album();
        album.setAlbums(albumStatsService.countAlbums());
        album.setPhotos(albumStatsService.countPhotos());
        album.setUploaders(albumStatsService.countUploaders());
        album.setUploadBatches(albumStatsService.countUploadBatches());
        // 视频数、查看与下载人次：系统里没有这些概念，给 null 不给 0
        vo.setAlbum(album);

        PlatformSummaryVO.Report report = new PlatformSummaryVO.Report();
        report.setReports(socialStatsService.countReports());
        report.setValid(socialStatsService.countReportsValid());
        report.setInvalid(socialStatsService.countReportsInvalid());
        report.setReporters(socialStatsService.countReporters());
        report.setBannedAccounts(volunteerQueryService.countDisabled());
        report.setPunishments(honorStatsService.countApprovedPunishments() + socialStatsService.countBans());
        vo.setReport(report);

        vo.setDonation(donateStatsService.summary());
        return vo;
    }
}
