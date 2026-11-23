package com.hengde.donate.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.constant.PermissionCode;
import com.hengde.donate.dto.MallVerifierAssignDTO;
import com.hengde.donate.service.MallVerifierService;
import com.hengde.donate.vo.MallVerifierVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端-核销员（{@code /a/donate/verifiers}，V3 卷批）。
 *
 * <p>复用 {@code donate:verify}：指派核销员的人就是管现场核销的人。
 * Row 8 F 说的是「企业设核销员」，enterprise 不在 V3，故 V3 由后台指派、企业自助留 V4。</p>
 *
 * <p>URL 骨架原写 {@code PUT /a/donate/verifiers}（整表替换），落地改为「逐个指派 / 撤销」：
 * 核销员是一个一个加的，整表替换会让两个管理员同时加人时互相覆盖掉对方刚加的那一个。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-积分商城核销员")
@RestController
@RequestMapping("/a/donate")
public class AdminMallVerifierController {

    private MallVerifierService verifierService;

    @Autowired
    public void setVerifierService(MallVerifierService verifierService) {
        this.verifierService = verifierService;
    }

    @Operation(summary = "核销员列表")
    @SaCheckPermission(value = PermissionCode.DONATE_VERIFY, type = "admin")
    @GetMapping("/verifiers")
    public Result<PageResult<MallVerifierVO>> list(PageQuery query) {
        return Result.ok(verifierService.list(query));
    }

    @Operation(summary = "指派核销员（须已实名且账号正常）")
    @SaCheckPermission(value = PermissionCode.DONATE_VERIFY, type = "admin")
    @PostMapping("/verifiers")
    public Result<Long> assign(@Valid @RequestBody MallVerifierAssignDTO dto) {
        return Result.ok(verifierService.assign(dto.getVolunteerId(), dto.getRemark(),
                StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "撤销核销员资格")
    @SaCheckPermission(value = PermissionCode.DONATE_VERIFY, type = "admin")
    @DeleteMapping("/verifiers/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        verifierService.remove(id);
        return Result.ok();
    }
}
