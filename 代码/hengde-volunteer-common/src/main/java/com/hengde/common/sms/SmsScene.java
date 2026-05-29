package com.hengde.common.sms;

/**
 * 短信验证码场景。
 *
 * <p>不同业务场景的验证码相互隔离（独立 Redis key、独立重发限流），
 * 调用 {@link VerifyCodeService} 时传入场景值。各领域共用此词表，避免硬编码字符串拼错。</p>
 *
 * @author hengde
 */
public interface SmsScene {

    /** 志愿者注册 */
    String REGISTER = "register";

    /** 管理端找回密码 */
    String RESET_PASSWORD = "reset-password";

    /** 修改/换绑手机号 */
    String CHANGE_PHONE = "change-phone";
}
