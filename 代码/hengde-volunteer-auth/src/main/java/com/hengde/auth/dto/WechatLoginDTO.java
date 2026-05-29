package com.hengde.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 微信小程序登录入参。
 *
 * @author hengde
 */
@Data
public class WechatLoginDTO {

    /** wx.login 拿到的临时登录凭证 code */
    @NotBlank(message = "code 不能为空")
    private String code;
}
