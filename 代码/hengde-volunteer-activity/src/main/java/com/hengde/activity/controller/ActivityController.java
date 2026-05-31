package com.hengde.activity.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.hengde.activity.constant.PermissionCode;
import com.hengde.activity.dto.ActivityCreateDTO;
import com.hengde.activity.service.ActivityService;
import com.hengde.activity.vo.ActivityVolunteerDetailVO;
import com.hengde.activity.vo.RecommendActivityVO;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-活动浏览。列表/详情仅需登录态（由 {@code /v/**} 路由拦截统一校验）。
 *
 * <p>发布活动是个例外：给「管理团队」志愿者用，需 {@code activity:publish} 权限点（V18 志愿者端 RBAC）。
 * 这里不带 {@code type="admin"}，故 {@code @SaCheckPermission} 走默认 {@code login} 域，鉴权数据来自
 * organization 的 StpInterface 志愿者分支（`volunteer_permission`）。复用与 {@code /a} 相同的
 * {@code ActivityService.publish}，操作人记当前志愿者。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-活动")
@RestController
@RequestMapping("/v/activity/activities")
public class ActivityController {

    private ActivityService activityService;

    @Autowired
    public void setActivityService(ActivityService activityService) {
        this.activityService = activityService;
    }

    @Operation(summary = "发布活动（管理团队志愿者，需 activity:publish 权限）")
    @SaCheckPermission(PermissionCode.ACTIVITY_PUBLISH)
    @PostMapping
    public Result<Long> publish(@RequestBody @Valid ActivityCreateDTO dto) {
        return Result.ok(activityService.publish(dto, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "活动列表/推荐（仅已发布；有名额优先排序，带报名人数/有名额标记）")
    @GetMapping
    public Result<PageResult<RecommendActivityVO>> list(PageQuery query,
                                                        @RequestParam(required = false) String keyword) {
        return Result.ok(activityService.listForVolunteer(query, keyword));
    }

    @Operation(summary = "活动详情（含时间段/报名须知，仅已发布）")
    @GetMapping("/{id}")
    public Result<ActivityVolunteerDetailVO> detail(@PathVariable Long id) {
        return Result.ok(activityService.detailForVolunteer(id));
    }
}
