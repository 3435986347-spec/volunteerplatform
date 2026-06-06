package com.hengde.organization.controller;

import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.result.Result;
import com.hengde.organization.service.AdminMeService;
import com.hengde.organization.vo.AdminMeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端-当前账号资料 + 权限码。登录态即可（{@code /a/**} 路由守卫已校验登录与启用），无需额外权限点。
 *
 * <p>前端登录后第一件事调本接口，据返回的 {@code permissionCodes}/{@code superAdmin} 渲染菜单与按钮。
 * 控制器落在 organization 而非 auth：见 {@link AdminMeService} 的跨模块说明。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-当前账号")
@RestController
@RequestMapping("/a/auth")
public class AdminMeController {

    private AdminMeService adminMeService;

    @Autowired
    public void setAdminMeService(AdminMeService adminMeService) {
        this.adminMeService = adminMeService;
    }

    @Operation(summary = "当前管理员资料 + 权限码（前端据此渲染菜单/按钮显隐）")
    @GetMapping("/me")
    public Result<AdminMeVO> me() {
        return Result.ok(adminMeService.me(StpAdminUtil.getLoginIdAsLong()));
    }
}
