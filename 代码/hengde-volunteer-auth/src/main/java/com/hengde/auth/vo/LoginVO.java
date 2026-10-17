package com.hengde.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录返回。
 *
 * @author hengde
 */
@Data
@AllArgsConstructor
public class LoginVO {

    /** Sa-Token token 值，后续请求放 Authorization 头 */
    private String token;

    /** 是否已实名注册（false 表示游客，前端需引导注册） */
    private boolean registered;

    /**
     * 这次登录<b>撤销了一份冷静期中的注销申请</b>（协会答复五 乙-4，2026-10-16：「7天冷静期即退出账号登录。
     * 再次登录时显示注销冷静期中。如登录则自动打断注销流程，账号恢复正常」）。
     * true 时小程序应提示「您的账号正处于注销冷静期，本次登录已撤销注销，账号恢复正常」。
     */
    private boolean deactivationCancelled;

    public LoginVO(String token, boolean registered) {
        this(token, registered, false);
    }
}
