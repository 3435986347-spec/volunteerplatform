package com.hengde.donate.controller;

import com.hengde.donate.service.DonateLogisticsPushService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 快递100 订阅推送的 webhook（{@code /callback/logistics/kuaidi100}，V3 物流推送批）。
 *
 * <p><b>无角色前缀、不进鉴权链，安全性完全由验签保证</b>——机制与 {@code TradeCallbackController} 相同：
 * Sa-Token 的拦截器注册在 {@code /**}，处理器里只对 {@code /v /a /e} 做 {@code check}，
 * 别的路径落不到任何 {@code check} 上才得以放行。api 里 {@code LogisticsCallbackApiTest} 钉住「免登录可达」。</p>
 *
 * <p><b>控制器只转发</b>，验签、单号核对、乱序处理都在 {@link DonateLogisticsPushService}。
 * 快递100 推的是 {@code application/x-www-form-urlencoded}，字段 {@code param / sign}；
 * 运单 id 是我们订阅时拼在回调地址上的 {@code sid}。表单字段由 Servlet 容器解析，
 * 不经过 MVC 的 JSON 转换器，所以不会撞上 trade 批 {@code @RequestBody String} 那个坑；
 * {@code param} 按表单解码后的原文参与验签，与快递100 签名时用的是同一串。</p>
 *
 * <p><b>应答</b>按快递100 的格式、不套 {@code Result}：成功 {@code result=true}，失败 {@code result=false}——
 * 快递100 按报文里的 {@code result} 判定要不要重推。</p>
 *
 * <p>⚠️ <b>应答直接写进 {@link HttpServletResponse}，不返回 {@code String}</b>：本项目的消息转换器链里，
 * 返回值是 {@code String}、声明 {@code produces=application/json} 时，是 Jackson 转换器接的手——
 * 它把整串报文当成<b>一个 JSON 字符串</b>再序列化一遍，对方收到的是 {@code "{\"result\":true,...}"}（带引号、转义），
 * 快递100 读不出 {@code result=true}，于是<b>每一条推送都被当成失败、无限重推</b>。
 * 这条是 {@code LogisticsCallbackApiTest} 走真实 HTTP 撞出来的，服务层用例看不到。</p>
 *
 * @author hengde
 */
@Tag(name = "快递100 推送回调（公开，靠验签）")
@RestController
@RequestMapping("/callback/logistics")
public class LogisticsCallbackController {

    private DonateLogisticsPushService pushService;

    @Autowired
    public void setPushService(DonateLogisticsPushService pushService) {
        this.pushService = pushService;
    }

    @Operation(summary = "快递100 订阅推送（MD5(param + salt) 验签，每张运单一个 salt）")
    @PostMapping("/kuaidi100")
    public void kuaidi100(@RequestParam(value = "sid", required = false) Long sid,
                          @RequestParam(value = "param", required = false) String param,
                          @RequestParam(value = "sign", required = false) String sign,
                          HttpServletResponse response) throws IOException {
        DonateLogisticsPushService.Ack ack = pushService.handleCallback(sid, param, sign);
        String message = ack.message() == null ? "" : ack.message().replace("\\", "\\\\").replace("\"", "\\\"");
        String body = "{\"result\":" + ack.success() + ",\"returnCode\":\""
                + (ack.success() ? "200" : "500") + "\",\"message\":\"" + message + "\"}";
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
