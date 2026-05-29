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
}
