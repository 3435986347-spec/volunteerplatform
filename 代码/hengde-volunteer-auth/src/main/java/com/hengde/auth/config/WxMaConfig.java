package com.hengde.auth.config;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.impl.WxMaServiceImpl;
import cn.binarywang.wx.miniapp.config.impl.WxMaDefaultConfigImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 微信小程序 SDK 配置：提供 {@link WxMaService} bean，用于 jsCode2Session 换 openid。
 *
 * <p>appid/secret 来自 {@code hengde.wechat.miniapp.*}（白标每实例独立）；
 * 留空默认值仅为让无配置的测试上下文能启动。</p>
 *
 * @author hengde
 */
@Configuration
public class WxMaConfig {

    @Bean
    public WxMaService wxMaService(@Value("${hengde.wechat.miniapp.appid:}") String appid,
                                   @Value("${hengde.wechat.miniapp.secret:}") String secret) {
        WxMaDefaultConfigImpl config = new WxMaDefaultConfigImpl();
        config.setAppid(appid);
        config.setSecret(secret);
        WxMaService service = new WxMaServiceImpl();
        service.setWxMaConfig(config);
        return service;
    }
}
