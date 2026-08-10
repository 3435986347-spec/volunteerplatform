package com.hengde.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.auth.entity.VolunteerNotification;
import com.hengde.auth.service.NotificationService;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-站内提示（{@code /v/notifications}）。
 *
 * <p><b>需求出处</b>：xlsx Row 41 F「…审核之后，<b>志愿者会收到提示</b>，并有 7 天申诉期」。</p>
 *
 * <p><b>本组接口刻意不受「拒绝使用本程序」处置的拦截</b>（见 {@code DenyAllUseGate}）：
 * 告知处罚成立与 7 天申诉期的那条提示本身就在这里，把它挡掉等于罚了人却不告诉他，
 * 与奖惩中心、申诉入口同一条口径。</p>
 *
 * <p>不挂权限点：这是每个志愿者看自己的东西，只需登录；收件人恒取当前登录态，
 * <b>不从入参取</b>——否则传个别人的 id 就能读别人的提示。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-站内提示")
@RestController
@RequestMapping("/v/notifications")
public class NotificationController {

    private NotificationService notificationService;

    @Autowired
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(summary = "我的提示（按时间倒序分页）")
    @GetMapping
    public Result<PageResult<VolunteerNotification>> myNotifications(PageQuery query) {
        return Result.ok(notificationService.myNotifications(StpUtil.getLoginIdAsLong(), query));
    }

    @Operation(summary = "未读条数（角标）")
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.ok(notificationService.unreadCount(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "标记一条为已读（重复调用不报错）")
    @PostMapping("/{id}/read")
    public Result<Boolean> markRead(@PathVariable Long id) {
        return Result.ok(notificationService.markRead(id, StpUtil.getLoginIdAsLong()));
    }
}
