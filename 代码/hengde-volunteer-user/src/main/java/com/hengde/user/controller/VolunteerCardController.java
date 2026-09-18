package com.hengde.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.result.Result;
import com.hengde.user.service.VolunteerCardService;
import com.hengde.user.vo.VolunteerCardVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-志愿者证（V4 志愿者证批，Row 26）。
 *
 * <p>⚠️ {@code GET /v/user/volunteer-cards/verify} 是<b>公开端点</b>（扫码的人不一定是本平台用户），
 * 登记在 api 的 {@code SaTokenConfigure} 志愿者端公开清单里；只收令牌，不收志愿者 id。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-志愿者证")
@RestController
@RequestMapping("/v/user")
public class VolunteerCardController {

    private VolunteerCardService cardService;

    @Autowired
    public void setCardService(VolunteerCardService cardService) {
        this.cardService = cardService;
    }

    @Operation(summary = "我的志愿者证（第一次打开时发证；带码图）")
    @GetMapping("/volunteer-card")
    public Result<VolunteerCardVO.Mine> mine() {
        return Result.ok(cardService.myCard(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "重置证件二维码（旧码当场失效，证件截图外流时用）")
    @PostMapping("/volunteer-card/reset")
    public Result<VolunteerCardVO.Mine> reset() {
        return Result.ok(cardService.reset(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "扫码核验（公开、免登录；只给头像 / 姓名只留姓 / 编号 / 注册时间 / 累计时长 / 活动次数）")
    @GetMapping("/volunteer-cards/verify")
    public Result<VolunteerCardVO.Public> verify(@Parameter(description = "码里的令牌（小程序码的 scene）") @RequestParam String token) {
        return Result.ok(cardService.verify(token));
    }
}
