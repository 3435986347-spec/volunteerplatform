package com.hengde.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 志愿者证（{@code hengde.user.card}，V4 志愿者证批）。
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.user.card")
public class VolunteerCardProperties {

    /** 小程序码打开的页面（不带 /，须是已发布版本里存在的页面；scene 里放令牌）。 */
    private String miniappPage = "pages/volunteer-card/verify";

    /** 小程序码的版本：release / trial / develop。 */
    private String miniappEnvVersion = "release";

    /**
     * 普通二维码的核验页地址（H5，后面拼 {@code ?token=}）。<b>小程序码生成不了时才用</b>（没配 {@code WX_APPID} 或微信接口失败）。
     * 留空时二维码内容是 {@code hengde-volunteer-card:令牌}——只有本小程序的扫一扫认得，微信直接扫打不开。
     */
    private String verifyUrl = "";

    /** 小程序码图片在 Redis 里缓存多久（秒）：令牌不变图就不变，不必每次打开都调微信接口。 */
    private long codeCacheSeconds = 7 * 24 * 3600L;
}
