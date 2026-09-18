package com.hengde.auth.service;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult;
import com.hengde.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 小程序身份：用 {@code wx.login} 拿到的临时 code 换<b>当前这个微信用户</b>的 openid（V3 商城快递批）。
 *
 * <p><b>为什么付款不能用 {@code volunteer.openid}</b>：手机号登录建出来的账号，openid 是合成的
 * （{@code p:} + 手机号哈希，只为满足非空唯一约束），根本不是微信的 openid；
 * 而小程序支付（JSAPI）要求下单时的 openid 与<b>此刻在小程序里点支付的那个微信用户</b>一致。
 * 所以付款前让小程序现取一个 code，服务端现换——既拿得到真 openid，也不信任客户端直接报上来的 openid。</p>
 *
 * <p>测试里用可编程的假实现替换它（没有小程序 appid 时真实现换不出来）。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class MiniappIdentityService {

    private WxMaService wxMaService;

    @Autowired
    public void setWxMaService(WxMaService wxMaService) {
        this.wxMaService = wxMaService;
    }

    /**
     * @param code 小程序 {@code wx.login} 返回的临时 code（5 分钟内有效、只能用一次）
     * @return openid
     */
    public String openidOf(String code) {
        if (!StringUtils.hasText(code)) {
            throw new BusinessException("缺少微信登录凭证，请重试");
        }
        try {
            WxMaJscode2SessionResult session = wxMaService.getUserService().getSessionInfo(code.trim());
            if (session == null || !StringUtils.hasText(session.getOpenid())) {
                throw new BusinessException("获取微信身份失败，请重试");
            }
            return session.getOpenid();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Auth] 付款前 jscode2session 失败", e);
            throw new BusinessException("获取微信身份失败，请重试");
        }
    }
}
