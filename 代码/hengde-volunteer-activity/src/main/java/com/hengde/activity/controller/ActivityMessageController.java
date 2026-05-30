package com.hengde.activity.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.activity.dto.ActivityMessageDTO;
import com.hengde.activity.service.ActivityMessageService;
import com.hengde.activity.vo.ActivityMessageVO;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-活动留言。登录态由 {@code /v/**} 路由统一校验，loginId 即 volunteer.id。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-活动留言")
@RestController
@RequestMapping("/v/activity")
public class ActivityMessageController {

    private ActivityMessageService activityMessageService;

    @Autowired
    public void setActivityMessageService(ActivityMessageService activityMessageService) {
        this.activityMessageService = activityMessageService;
    }

    @Operation(summary = "活动留言列表")
    @GetMapping("/activities/{id}/messages")
    public Result<PageResult<ActivityMessageVO>> list(@PathVariable Long id, PageQuery query) {
        return Result.ok(activityMessageService.list(id, query));
    }

    @Operation(summary = "发表活动留言")
    @PostMapping("/activities/{id}/messages")
    public Result<Long> post(@PathVariable Long id, @RequestBody @Valid ActivityMessageDTO dto) {
        return Result.ok(activityMessageService.post(id, StpUtil.getLoginIdAsLong(), dto.getContent()));
    }
}
