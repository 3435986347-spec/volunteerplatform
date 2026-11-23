package com.hengde.social.service;

import com.hengde.auth.service.SanctionQueryService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 社区的四层门（V4规划 D1）：① 登录态（路由过滤器，已有）/ ② 已验手机号才能看 / ③ 已实名才能发帖、评论、点赞、关注 /
 * ④ 处置闸门（发帖 / 评论 / 点赞各一个能力域，{@code COMMUNITY} 与 {@code ALL} 蕴含它们）。
 *
 * <p><b>④ 必须在写入的事务里调</b>——{@link SanctionQueryService#assertNotRestricted} 是当前读 + 父行共享锁，
 * autocommit 下锁随语句释放，闸门看着还在、串行化边界已经没了（V2 第 5 批那一课）。</p>
 *
 * @author hengde
 */
@Service
public class SocialGateService {

    private VolunteerQueryService volunteerQueryService;
    private SanctionQueryService sanctionQueryService;

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setSanctionQueryService(SanctionQueryService sanctionQueryService) {
        this.sanctionQueryService = sanctionQueryService;
    }

    /** ② 看帖：Row 23 D「登录验证手机号才能看帖子」——微信登录的账号可能根本没绑手机号，登录态挡不住。 */
    public void requireViewer(Long volunteerId) {
        if (!volunteerQueryService.hasVerifiedPhone(volunteerId)) {
            throw new BusinessException("请先验证手机号后再看社区");
        }
    }

    /** ③ 发帖 / 评论 / 点赞 / 关注：Row 23 D「未实名的不能发帖、评论、点赞等操作」。 */
    public void requireActor(Long volunteerId) {
        if (!volunteerQueryService.filterActiveRegistered(List.of(volunteerId)).contains(volunteerId)) {
            throw new BusinessException("实名注册后才能发帖、评论、点赞和关注");
        }
    }

    /** ④ 处置闸门。<b>只能在事务内调用。</b> */
    public void assertNotRestricted(Long volunteerId, int scope, String action) {
        sanctionQueryService.assertNotRestricted(volunteerId, scope, action);
    }
}
