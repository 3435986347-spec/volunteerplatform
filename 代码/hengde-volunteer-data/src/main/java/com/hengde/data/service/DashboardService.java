package com.hengde.data.service;

import com.hengde.activity.service.ActivityStatsService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.data.vo.DashboardVO;
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
}
