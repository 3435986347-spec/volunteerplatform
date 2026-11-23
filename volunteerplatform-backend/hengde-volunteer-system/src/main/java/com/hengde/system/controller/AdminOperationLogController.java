package com.hengde.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.system.constant.PermissionCode;
import com.hengde.system.dto.SystemDTOs;
import com.hengde.system.service.OperationLogService;
import com.hengde.system.support.OperationLogRecorder;
import com.hengde.system.vo.SystemVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 管理端-操作日志（Row 62）。查看挂 {@code system:log}（**默认不授任何人**，超管通配）。
 *
 * <p>页面访问由前端上报：后端看不见纯前端的路由切换，而 Row 62 要的正是「谁点击了哪个页面」。
 * 上报端点<b>只需登录</b>——它是前端的例行动作，挂权限点会让没有日志查看权的人上报不了自己的足迹。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-操作日志")
@RestController
@RequestMapping("/a/system")
public class AdminOperationLogController {

    private OperationLogService operationLogService;
    private OperationLogRecorder recorder;

    @Autowired
    public void setOperationLogService(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @Autowired
    public void setRecorder(OperationLogRecorder recorder) {
        this.recorder = recorder;
    }

    @Operation(summary = "操作日志（?logType= 1操作/2页面访问、?actorType=、?actorId=、?keyword=、?from=、?to=）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_LOG, type = "admin")
    @GetMapping("/logs")
    public Result<PageResult<SystemVOs.OperationLog>> logs(
            @Parameter(description = "1 操作 / 2 页面访问") @RequestParam(required = false) Integer logType,
            @RequestParam(required = false) Integer actorType,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            PageQuery query) {
        return Result.ok(operationLogService.search(logType, actorType, actorId, keyword, from, to, query));
    }

    @Operation(summary = "上报一次页面访问（前端在切换页面时调；仅需登录）")
    @PostMapping("/page-views")
    public Result<Void> pageView(@Valid @RequestBody SystemDTOs.PageView dto, HttpServletRequest request) {
        recorder.recordPageView(request, StpAdminUtil.getLoginIdAsLong(), dto.getPage(), dto.getPath());
        return Result.ok();
    }
}
