package com.hengde.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.auth.service.NotifyPreferenceService;
import com.hengde.auth.vo.NotifyPreferenceView;
import com.hengde.common.result.Result;
import com.hengde.user.dto.NotifyPreferenceDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-订阅通知（{@code /v/user/notify-preferences}，Row 48 安全中心）。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-订阅通知")
@RestController
@RequestMapping("/v/user/notify-preferences")
public class NotifyPreferenceController {

    private NotifyPreferenceService preferenceService;

    @Autowired
    public void setPreferenceService(NotifyPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @Operation(summary = "我的订阅（全部话题；optional=false 的不能关）")
    @GetMapping
    public Result<List<NotifyPreferenceView>> mine() {
        return Result.ok(preferenceService.mine(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "打开 / 关闭一个话题的短信提醒（只影响短信；站内提示照常留存）")
    @PutMapping
    public Result<Void> set(@Valid @RequestBody NotifyPreferenceDTO dto) {
        preferenceService.set(StpUtil.getLoginIdAsLong(), dto.getTopic(), dto.getSmsEnabled());
        return Result.ok();
    }
}
