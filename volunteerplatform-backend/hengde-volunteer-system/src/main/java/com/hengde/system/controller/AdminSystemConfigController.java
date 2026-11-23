package com.hengde.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.result.Result;
import com.hengde.system.constant.PermissionCode;
import com.hengde.system.dto.SystemDTOs;
import com.hengde.system.service.SystemConfigService;
import com.hengde.system.vo.SystemVOs;
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
 * 管理端-系统配置：界面水印（Row 78）、后台菜单排序（Row 77）、编号段一览（Row 75）。
 *
 * <p><b>读只需登录、写要 {@code system:config}</b>：每个进后台的人都要拿到水印与菜单顺序才渲染得出界面，
 * 而「改显示逻辑」是 Row 78 F 明写的最高权限动作。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-系统配置")
@RestController
@RequestMapping("/a/system")
public class AdminSystemConfigController {

    private SystemConfigService configService;

    @Autowired
    public void setConfigService(SystemConfigService configService) {
        this.configService = configService;
    }

    @Operation(summary = "界面水印设置 + 给当前账号算好的水印文字（仅需登录）")
    @GetMapping("/watermark")
    public Result<SystemVOs.Watermark> watermark() {
        return Result.ok(configService.watermark(StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "改界面水印（显示哪几样、字号、透明度、角度）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_CONFIG, type = "admin")
    @PutMapping("/watermark")
    public Result<Void> saveWatermark(@Valid @RequestBody SystemDTOs.WatermarkSave dto) {
        configService.saveWatermark(dto, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "后台菜单排序（仅需登录）")
    @GetMapping("/menu-order")
    public Result<SystemVOs.MenuOrder> menuOrder() {
        return Result.ok(configService.menuOrder());
    }

    @Operation(summary = "改后台菜单排序（整份覆盖；菜单键不能重复）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_CONFIG, type = "admin")
    @PutMapping("/menu-order")
    public Result<Void> saveMenuOrder(@Valid @RequestBody SystemDTOs.MenuOrderSave dto) {
        configService.saveMenuOrder(dto, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "编号段一览（Row 75：十位编号，前三位是功能段）")
    @SaCheckPermission(value = PermissionCode.SYSTEM_CONFIG, type = "admin")
    @GetMapping("/serials")
    public Result<List<SystemVOs.Serial>> serials() {
        return Result.ok(configService.serials());
    }
}
