package com.hengde.system.controller;

import cn.dev33.satoken.stp.StpLogic;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.result.Result;
import com.hengde.system.service.FileVaultService;
import com.hengde.system.vo.SystemVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 打开一条分享链接（Row 71「可以分享、下载」+「后台可以设置是否登录管理员账号才能打开」）。
 *
 * <p><b>这条路径在 {@code SaTokenConfigure} 里登记为公开</b>（像志愿者证核验、支付回调那样），
 * 因为「不用登录也能打开」是需求里的一个开关；要不要登录由<b>这条分享自己</b>说了算，服务层再核一次。
 * 没登录时这里不抛 401，而是把「有没有登录态」如实传给服务层——挡不挡由分享的设置决定。</p>
 *
 * @author hengde
 */
@Tag(name = "文件分享")
@RestController
@RequestMapping("/share")
public class FileShareController {

    private FileVaultService vaultService;

    @Autowired
    public void setVaultService(FileVaultService vaultService) {
        this.vaultService = vaultService;
    }

    @Operation(summary = "打开分享（要登录的分享没登录时报错；每打开一次记一次下载数）")
    @GetMapping("/files/{token}")
    public Result<SystemVOs.SharedFile> open(@PathVariable String token) {
        return Result.ok(vaultService.openShare(token, currentAdminId()));
    }

    /** 没登录就是 null——这里不能抛 401，那样「不用登录也能打开」的分享就打不开了。 */
    private static Long currentAdminId() {
        try {
            StpLogic logic = StpAdminUtil.STP_LOGIC;
            return logic.isLogin() ? Long.parseLong(logic.getLoginId().toString()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
