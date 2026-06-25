package com.hengde.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.result.Result;
import com.hengde.user.dto.ChangePhoneDTO;
import com.hengde.user.dto.MyProfileUpdateDTO;
import com.hengde.user.service.MyProfileService;
import com.hengde.user.vo.MyProfileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 志愿者端-我的资料（{@code /v/user/profile}）。本人查看 / 修改自己的资料。
 *
 * <p>默认 {@code StpUtil}（loginType=login），loginId=志愿者 id；<b>仅需登录</b>（看/改自己的资料，不挂权限点）。
 * 改手机号需短信验证，不在此（单独流程）；姓名/身份证等实名字段不可自助改（需后台超管 {@code /a/user/volunteers}）。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-我的资料")
@RestController
@RequestMapping("/v/user")
public class MyProfileController {

    private MyProfileService myProfileService;

    @Autowired
    public void setMyProfileService(MyProfileService myProfileService) {
        this.myProfileService = myProfileService;
    }

    @Operation(summary = "我的资料（本人完整资料 + 时长/积分/小组/分队）")
    @GetMapping("/profile")
    public Result<MyProfileVO> profile() {
        return Result.ok(myProfileService.getMyProfile(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "修改我的资料（头像/学校/年级/政治面貌/地址/紧急联系方式，部分更新）")
    @PatchMapping("/profile")
    public Result<Void> update(@RequestBody @Valid MyProfileUpdateDTO dto) {
        myProfileService.updateMyProfile(StpUtil.getLoginIdAsLong(), dto);
        return Result.ok();
    }

    @Operation(summary = "修改/换绑手机号（需新手机号短信验证，scene=change-phone）")
    @PutMapping("/phone")
    public Result<Void> changePhone(@RequestBody @Valid ChangePhoneDTO dto) {
        myProfileService.changePhone(StpUtil.getLoginIdAsLong(), dto.getPhone(), dto.getSmsCode());
        return Result.ok();
    }
}
