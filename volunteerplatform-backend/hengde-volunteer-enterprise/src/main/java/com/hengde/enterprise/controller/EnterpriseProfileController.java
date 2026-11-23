package com.hengde.enterprise.controller;

import com.hengde.auth.config.StpEnterpriseUtil;
import com.hengde.common.result.Result;
import com.hengde.enterprise.dto.EnterpriseDTOs;
import com.hengde.enterprise.service.EnterpriseAccountService;
import com.hengde.enterprise.vo.EnterpriseVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业端-我的企业（{@code /e/enterprise/profile}，V4 爱心企业批）。待审核与被驳回的企业也能用这组端点（其余企业端接口要审核通过）。
 *
 * @author hengde
 */
@Tag(name = "企业端-我的企业")
@RestController
@RequestMapping("/e/enterprise/profile")
public class EnterpriseProfileController {

    private EnterpriseAccountService accountService;

    @Autowired
    public void setAccountService(EnterpriseAccountService accountService) {
        this.accountService = accountService;
    }

    @Operation(summary = "我的企业资料与入驻审核状态（含驳回 / 暂停原因）")
    @GetMapping
    public Result<EnterpriseVOs.Account> mine() {
        return Result.ok(accountService.mine(StpEnterpriseUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "修改资料（头像 / 介绍 / 地址 / 对外电话随时可改；企业名称 / 信用代码 / 项目负责人只在待审核或驳回时可改）")
    @PutMapping
    public Result<EnterpriseVOs.Account> update(@Valid @RequestBody EnterpriseDTOs.Profile dto) {
        return Result.ok(accountService.updateProfile(StpEnterpriseUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "被驳回后重新提交入驻申请")
    @PostMapping("/resubmit")
    public Result<EnterpriseVOs.Account> resubmit() {
        return Result.ok(accountService.resubmit(StpEnterpriseUtil.getLoginIdAsLong()));
    }
}
