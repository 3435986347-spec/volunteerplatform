package com.hengde.api.controller;

import com.hengde.trade.gateway.PaymentGateway;
import com.hengde.trade.service.TradeCallbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 微信支付的 webhook（{@code /callback/trade/wechat/**}，V3 trade 批）。
 *
 * <p><b>无角色前缀，所以不进鉴权链</b>——安全性完全由<b>验签</b>保证，不是由登录态。
 * ⚠️ 机制要说准：{@code SaTokenConfigure} 的拦截器其实注册在 {@code /**} 上，
 * 只是处理器内部只对 {@code /v/**} {@code /a/**} {@code /e/**} 做 {@code SaRouter.match(...).check(...)}，
 * 别的路径落不到任何一条 {@code check} 上才得以放行。<b>哪天有人往处理器里加一条全局规则，
 * 这两条 webhook 会当场变成 401 且没有任何征兆</b>——所以 api 里有一条用例专门钉住「回调免登录可达」。</p>
 *
 * <p><b>控制器只做两件事：读原始报文、转发</b>（V3规划 D1 的规矩 2）。验签与解密在 trade 的 service 里，
 * 否则支付逻辑会一点点长到 api 来。</p>
 *
 * <p><b>应答语义</b>：处理完（含之前就处理过）回 200 {@code SUCCESS}，让渠道别再重投；
 * <b>验签失败 / 金额不符 / 单号不认识回非 200 的 FAIL</b>——那几种要么是伪造、要么是我们这边出了问题，
 * 回 SUCCESS 等于把它藏起来，而渠道的重投正是我们需要的第二次机会。</p>
 *
 * @author hengde
 */
@Tag(name = "微信支付回调（公开，靠验签）")
@RestController
@RequestMapping("/callback/trade/wechat")
public class TradeCallbackController {

    private TradeCallbackService callbackService;

    @Autowired
    public void setCallbackService(TradeCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @Operation(summary = "支付结果通知（APIv3 验签 + 解密）")
    @PostMapping("/pay")
    public void pay(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(response, callbackService.handlePay(headersOf(request), rawBody(request)));
    }

    @Operation(summary = "退款结果通知（APIv3 验签 + 解密）")
    @PostMapping("/refund")
    public void refund(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(response, callbackService.handleRefund(headersOf(request), rawBody(request)));
    }

    /**
     * 读原始报文。
     *
     * <p>⚠️ <b>不能用 {@code @RequestBody String}</b>：请求的 Content-Type 是 {@code application/json}，
     * 而 MVC 前置的 Jackson 转换器会把整个报文当成「一个 JSON 字符串」去解析，
     * 于是合法的通知体（一个 JSON 对象）直接以 400「请求体格式错误」被挡在控制器之外——
     * 微信那边只看到「通知失败」，我们这边连日志都没有。<b>验签算的也正是这一串原文</b>，
     * 所以这里按字节读、按 UTF-8 转字符串，一个字符都不加工。</p>
     */
    private static String rawBody(HttpServletRequest request) {
        try (java.io.InputStream in = request.getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /** APIv3 的四个验签头。名字大小写不敏感（Servlet 规范），照微信文档的写法取。 */
    private static PaymentGateway.CallbackHeaders headersOf(HttpServletRequest request) {
        return new PaymentGateway.CallbackHeaders(
                request.getHeader("Wechatpay-Serial"),
                request.getHeader("Wechatpay-Signature"),
                request.getHeader("Wechatpay-Timestamp"),
                request.getHeader("Wechatpay-Nonce"));
    }

    /**
     * 写渠道要的应答体。
     *
     * <p>失败用 {@code 500}：微信按「非 200」判定失败并重投。<b>不要图省事回 200 + FAIL</b>——
     * 那样渠道认为已经收妥，再也不会重投，而我们这边其实没处理成。</p>
     *
     * <p>⚠️ <b>直接写进 {@link HttpServletResponse}，不返回 {@code String}</b>：本项目的消息转换器链里，
     * {@code String} 返回值配 {@code application/json} 是 Jackson 转换器接的手，它会把整串报文再序列化成
     * 一个 JSON 字符串（带引号、转义）——对方拿到的不是 {@code {"code":"SUCCESS"}} 而是 {@code "{\"code\":...}"}。
     * 快递100 回调在物流推送批被 HTTP 用例撞出这个问题（那边按报文判定成败，会无限重推），这里同形修掉。</p>
     */
    private static void write(HttpServletResponse response, TradeCallbackService.Ack ack) throws IOException {
        String body = ack.success()
                ? "{\"code\":\"SUCCESS\",\"message\":\"成功\"}"
                : "{\"code\":\"FAIL\",\"message\":\"" + escape(ack.message()) + "\"}";
        response.setStatus(ack.success() ? HttpServletResponse.SC_OK : HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
