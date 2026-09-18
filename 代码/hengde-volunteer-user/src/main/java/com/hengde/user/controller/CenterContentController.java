package com.hengde.user.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.result.Result;
import com.hengde.organization.constant.PermissionCode;
import com.hengde.user.dto.CenterContentSaveDTO;
import com.hengde.user.service.CenterContentService;
import com.hengde.user.vo.CenterContentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人中心内容：志愿者端 {@code /v/user/center-contents/{key}} 读，管理端 {@code /a/user/center-contents/{key}} 读写
 * （key：insurance 我的保险 / customer-service 联系客服）。
 *
 * @author hengde
 */
@Tag(name = "个人中心内容（我的保险 / 联系客服）")
@RestController
public class CenterContentController {

    private CenterContentService contentService;

    @Autowired
    public void setContentService(CenterContentService contentService) {
        this.contentService = contentService;
    }

    @Operation(summary = "志愿者看个人中心内容（还没设置时 data 为空）")
    @GetMapping("/v/user/center-contents/{key}")
    public Result<CenterContentVO> get(@PathVariable String key) {
        return Result.ok(contentService.get(key));
    }

    @Operation(summary = "后台读个人中心内容")
    @SaCheckPermission(value = PermissionCode.USER_CENTER_CONTENT, type = "admin")
    @GetMapping("/a/user/center-contents/{key}")
    public Result<CenterContentVO> adminGet(@PathVariable String key) {
        return Result.ok(contentService.get(key));
    }

    @Operation(summary = "后台设置个人中心内容（整份替换；图片先经 /a/files/upload?dir=center）")
    @SaCheckPermission(value = PermissionCode.USER_CENTER_CONTENT, type = "admin")
    @PutMapping("/a/user/center-contents/{key}")
    public Result<Void> save(@PathVariable String key, @Valid @RequestBody CenterContentSaveDTO dto) {
        contentService.save(key, dto, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
