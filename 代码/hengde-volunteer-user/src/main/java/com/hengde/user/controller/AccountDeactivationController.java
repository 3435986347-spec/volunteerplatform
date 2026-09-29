package com.hengde.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.auth.service.VolunteerDeactivationService;
import com.hengde.common.result.Result;
import com.hengde.user.dto.DeactivationRequestDTO;
import com.hengde.user.vo.DeactivationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-注销账号（安全中心）。
 *
 * <p>协会 2026-09-16：注销＝退出协会；7 天冷静期（期内照常可用、可撤销）；到期后手机号释放、身份证不能再注册；
 * 想回来时由理事会在后台恢复。逻辑在 auth 的 {@code VolunteerDeactivationService}。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-注销账号")
@RestController
@RequestMapping("/v/user/deactivation")
public class AccountDeactivationController {

    private VolunteerDeactivationService deactivationService;

    @Autowired
    public void setDeactivationService(VolunteerDeactivationService deactivationService) {
        this.deactivationService = deactivationService;
    }

    @Operation(summary = "我的注销状态（是否在冷静期、何时生效）")
    @GetMapping
    public Result<DeactivationVO> status() {
        return Result.ok(toVO(deactivationService.status(StpUtil.getLoginIdAsLong())));
    }

    @Operation(summary = "申请注销（验证码发到本人手机号，scene=account-deactivate）；进入冷静期，期内可撤销")
    @PostMapping
    public Result<DeactivationVO> request(@RequestBody @Valid DeactivationRequestDTO dto) {
        long id = StpUtil.getLoginIdAsLong();
        deactivationService.request(id, dto.getSmsCode());
        return Result.ok(toVO(deactivationService.status(id)));
    }

    @Operation(summary = "撤销注销（冷静期内）")
    @DeleteMapping
    public Result<Void> cancel() {
        deactivationService.cancel(StpUtil.getLoginIdAsLong());
        return Result.ok();
    }

    private static DeactivationVO toVO(VolunteerDeactivationService.Status s) {
        DeactivationVO vo = new DeactivationVO();
        vo.setPending(s.pending());
        vo.setRequestTime(s.requestTime());
        vo.setEffectiveTime(s.effectiveTime());
        vo.setCoolingDays(s.coolingDays());
        return vo;
    }
}
