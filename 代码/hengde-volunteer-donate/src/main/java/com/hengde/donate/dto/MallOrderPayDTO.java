package com.hengde.donate.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发起付款（商城快递批）。
 *
 * <p><b>只收 {@code wx.login} 的临时 code，不收 openid</b>：付款人的 openid 由服务端现换，
 * 不信任客户端报上来的值；手机号登录的账号库里也根本没有真的 openid。</p>
 *
 * @author hengde
 */
@Data
@Schema(description = "发起付款")
@JsonIgnoreProperties(ignoreUnknown = true)
public class MallOrderPayDTO {

    @Schema(description = "小程序 wx.login 返回的临时 code")
    @NotBlank(message = "缺少微信登录凭证")
    private String code;
}
