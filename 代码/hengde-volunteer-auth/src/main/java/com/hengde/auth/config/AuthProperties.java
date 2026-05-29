package com.hengde.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * auth 领域配置，绑定 {@code hengde.auth.*}。
 *
 * <p>实名二要素、企业微信群校验两个外部集成默认关闭（dev/test 走 no-op 放行），
 * 拿到真实密钥后置 true 并接入真实实现。</p>
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.auth")
public class AuthProperties {

    /** 是否启用真实身份证二要素实名校验（腾讯云）。false 时直接放行 */
    private boolean realnameEnabled = false;

    /** 是否启用真实企业微信群成员校验。false 时直接放行 */
    private boolean weworkGroupEnabled = false;

    /** 未入群时返回给前端弹出的企业微信群二维码地址 */
    private String weworkGroupQrUrl;

    /** 是否在启动时初始化超级管理员（不存在任何超管时才创建） */
    private boolean initSuperAdmin = true;

    /** 初始超级管理员账号 */
    private String superAdminUsername = "admin";

    /** 初始超级管理员密码（首版默认值，上线后须立即登录修改） */
    private String superAdminPassword = "admin123";
}
