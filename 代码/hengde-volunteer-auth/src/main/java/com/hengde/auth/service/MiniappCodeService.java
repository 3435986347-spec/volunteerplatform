package com.hengde.auth.service;

import cn.binarywang.wx.miniapp.api.WxMaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 小程序码（{@code wxacode.getUnlimited}，V4 志愿者证批）。放 auth 是因为 {@link WxMaService} 在这里配置。
 *
 * <p><b>生成不了就返回 null、不抛</b>：没配 {@code WX_APPID}、页面没发布、微信接口限流都会失败，调用方据此回退普通二维码——
 * 证件打不开比二维码换一种样式糟得多。⚠️ 没有真实 AppID，<b>这条调用从未端到端跑通过</b>。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class MiniappCodeService {

    /** scene 参数的上限（微信文档：最多 32 个可见字符）。 */
    public static final int SCENE_MAX = 32;

    private WxMaService wxMaService;

    @Autowired
    public void setWxMaService(WxMaService wxMaService) {
        this.wxMaService = wxMaService;
    }

    /** 配没配小程序 AppID / Secret。 */
    public boolean configured() {
        try {
            return wxMaService.getWxMaConfig() != null
                    && StringUtils.hasText(wxMaService.getWxMaConfig().getAppid())
                    && StringUtils.hasText(wxMaService.getWxMaConfig().getSecret());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @return PNG 字节；没配置或微信返回错误时 null
     */
    public byte[] unlimited(String scene, String page, String envVersion) {
        if (!configured() || !StringUtils.hasText(scene) || scene.length() > SCENE_MAX) {
            return null;
        }
        try {
            return wxMaService.getQrcodeService().createWxaCodeUnlimitBytes(scene, page, false,
                    StringUtils.hasText(envVersion) ? envVersion : "release", 430, false, null, false);
        } catch (Exception e) {
            log.warn("[MiniappCode] 生成小程序码失败 page={}（回退普通二维码）：{}", page, e.getMessage());
            return null;
        }
    }
}
