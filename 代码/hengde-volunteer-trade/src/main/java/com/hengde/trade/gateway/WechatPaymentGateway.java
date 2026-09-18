package com.hengde.trade.gateway;

import com.github.binarywang.wxpay.bean.notify.SignatureHeader;
import com.github.binarywang.wxpay.bean.notify.WxPayNotifyV3Result;
import com.github.binarywang.wxpay.bean.notify.WxPayRefundNotifyV3Result;
import com.github.binarywang.wxpay.bean.request.WxPayRefundV3Request;
import com.github.binarywang.wxpay.bean.request.WxPayUnifiedOrderV3Request;
import com.github.binarywang.wxpay.bean.result.WxPayOrderQueryV3Result;
import com.github.binarywang.wxpay.bean.result.WxPayRefundV3Result;
import com.github.binarywang.wxpay.bean.result.WxPayUnifiedOrderV3Result;
import com.github.binarywang.wxpay.bean.result.enums.TradeTypeEnum;
import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.binarywang.wxpay.service.impl.WxPayServiceImpl;
import com.hengde.trade.config.TradeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * 微信支付 APIv3 的渠道实现。
 *
 * <p><b>默认关闭</b>（要协会的商户资质，《协会待确认清单-v3》⑩）。关闭时 {@link #enabled()} 返回 false，
 * 调用方一律拒绝下单并说清原因，<b>绝不伪造成功</b>——同快递100 未开通时「如实说未开通」。</p>
 *
 * <p><b>客户端懒加载</b>（仿 {@code SmsServiceImpl} / {@code Kuaidi100LogisticsClient}）：
 * 配置齐全才构造，构造一次复用；配置不全时连构造都不做，免得启动期就炸在一个还没开通的能力上。</p>
 *
 * <p><b>验签与解密在这里</b>（V3规划 D1 的规矩 2）：{@code parseOrderNotifyV3Result} 内部完成
 * APIv3 签名校验与 AES-GCM 解密，失败抛 {@link PaymentException}——<b>一律拒绝，不做「尽力而为地解析」</b>。</p>
 *
 * <p>⚠️ <b>没有商户资质与备案域名，这条实现无法端到端验证</b>：本模块的用例跑的是
 * {@code FakePaymentGateway}，证明的是我们自己的状态机。<b>别把用例全绿当成支付通了。</b></p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class WechatPaymentGateway implements PaymentGateway {

    private TradeProperties properties;
    private volatile WxPayService payService;

    @Autowired
    public void setProperties(TradeProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean enabled() {
        TradeProperties.Wechat w = properties.getWechat();
        return w.isEnabled()
                && StringUtils.hasText(w.getMchId())
                && StringUtils.hasText(w.getAppId())
                && StringUtils.hasText(w.getApiV3Key())
                && StringUtils.hasText(w.getMerchantSerialNumber())
                && StringUtils.hasText(w.getPrivateKey());
    }

    @Override
    public PrepayResult prepay(String outTradeNo, int amountFen, String subject, String payerOpenid,
                               LocalDateTime expireAt) {
        requireEnabled();
        TradeProperties.Wechat w = properties.getWechat();
        WxPayUnifiedOrderV3Request req = new WxPayUnifiedOrderV3Request();
        req.setOutTradeNo(outTradeNo);
        req.setDescription(subject);
        req.setNotifyUrl(w.getPayNotifyUrl());
        req.setAmount(new WxPayUnifiedOrderV3Request.Amount().setTotal(amountFen).setCurrency("CNY"));
        req.setPayer(new WxPayUnifiedOrderV3Request.Payer().setOpenid(payerOpenid));
        if (expireAt != null) {
            // APIv3 的 time_expire 要 RFC 3339；用系统默认时区偏移拼出来
            req.setTimeExpire(OffsetDateTime.of(expireAt, OffsetDateTime.now().getOffset()).toString());
        }
        try {
            WxPayUnifiedOrderV3Result.JsapiResult r = client().createOrderV3(TradeTypeEnum.JSAPI, req);
            return new PrepayResult(r.getPackageValue(), r.getPaySign(), r.getNonceStr(),
                    r.getTimeStamp(), r.getSignType());
        } catch (WxPayException e) {
            throw new PaymentException("微信下单失败：" + e.getMessage(), e);
        }
    }

    @Override
    public QueryResult query(String outTradeNo) {
        requireEnabled();
        try {
            WxPayOrderQueryV3Result r = client().queryOrderV3(outTradeNo, null);
            if (r == null) {
                return QueryResult.notFound();
            }
            boolean paid = "SUCCESS".equalsIgnoreCase(r.getTradeState());
            Integer total = r.getAmount() == null ? null : r.getAmount().getTotal();
            return new QueryResult(true, paid, r.getTransactionId(), total,
                    parseTime(r.getSuccessTime()), r.toString());
        } catch (WxPayException e) {
            // 「查无此单」是正常结果，不是异常：微信用 ORDERNOTEXIST 表示它
            if (e.getErrCode() != null && e.getErrCode().contains("ORDERNOTEXIST")) {
                return QueryResult.notFound();
            }
            throw new PaymentException("微信查单失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void close(String outTradeNo) {
        requireEnabled();
        try {
            client().closeOrderV3(outTradeNo);
        } catch (WxPayException e) {
            throw new PaymentException("微信关单失败：" + e.getMessage(), e);
        }
    }

    @Override
    public RefundResult refund(String outTradeNo, String outRefundNo, int refundFen, int totalFen, String reason) {
        requireEnabled();
        WxPayRefundV3Request req = new WxPayRefundV3Request();
        req.setOutTradeNo(outTradeNo);
        req.setOutRefundNo(outRefundNo);
        req.setReason(reason);
        req.setNotifyUrl(properties.getWechat().getRefundNotifyUrl());
        req.setAmount(new WxPayRefundV3Request.Amount().setRefund(refundFen).setTotal(totalFen).setCurrency("CNY"));
        try {
            WxPayRefundV3Result r = client().refundV3(req);
            // 渠道同步返回的 status 只代表受理结果，最终以回调 / 查询为准（调用方按异步处理）
            return new RefundResult(r.getRefundId(), true, r.toString());
        } catch (WxPayException e) {
            throw new PaymentException("微信退款失败：" + e.getMessage(), e);
        }
    }

    @Override
    public CallbackResult verifyAndDecrypt(CallbackHeaders headers, String body) {
        requireEnabled();
        if (headers == null || !StringUtils.hasText(headers.signature()) || !StringUtils.hasText(headers.serial())) {
            throw new PaymentException("回调缺少验签头");
        }
        SignatureHeader sh = new SignatureHeader();
        sh.setSerial(headers.serial());
        sh.setSignature(headers.signature());
        sh.setTimeStamp(headers.timestamp());
        sh.setNonce(headers.nonce());
        // 支付通知与退款通知的报文结构不同，先按支付解，解不出来再按退款解——
        // 两个入口（/pay 与 /refund）本就分开，这里只是兜住投递串了口的情况
        try {
            WxPayNotifyV3Result pay = client().parseOrderNotifyV3Result(body, sh);
            WxPayNotifyV3Result.DecryptNotifyResult d = pay.getResult();
            return new CallbackResult(CallbackResult.KIND_PAY, d.getOutTradeNo(), d.getTransactionId(),
                    null, null, "SUCCESS".equalsIgnoreCase(d.getTradeState()),
                    d.getAmount() == null ? null : d.getAmount().getTotal(),
                    parseTime(d.getSuccessTime()),
                    d.getPayer() == null ? null : d.getPayer().getOpenid(), body);
        } catch (WxPayException payFailure) {
            try {
                WxPayRefundNotifyV3Result refund = client().parseRefundNotifyV3Result(body, sh);
                WxPayRefundNotifyV3Result.DecryptNotifyResult d = refund.getResult();
                return new CallbackResult(CallbackResult.KIND_REFUND, d.getOutTradeNo(), d.getTransactionId(),
                        d.getOutRefundNo(), d.getRefundId(), "SUCCESS".equalsIgnoreCase(d.getRefundStatus()),
                        d.getAmount() == null ? null : d.getAmount().getRefund(),
                        parseTime(d.getSuccessTime()), null, body);
            } catch (WxPayException refundFailure) {
                throw new PaymentException("回调验签或解密失败：" + payFailure.getMessage(), payFailure);
            }
        }
    }

    // ---------- 内部 ----------

    private void requireEnabled() {
        if (!enabled()) {
            throw new PaymentException("微信支付未开通");
        }
    }

    /** 懒加载并复用客户端。 */
    private WxPayService client() {
        WxPayService local = payService;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (payService == null) {
                TradeProperties.Wechat w = properties.getWechat();
                WxPayConfig config = new WxPayConfig();
                config.setAppId(w.getAppId());
                config.setMchId(w.getMchId());
                config.setApiV3Key(w.getApiV3Key());
                config.setCertSerialNo(w.getMerchantSerialNumber());
                config.setPrivateKeyString(w.getPrivateKey());
                config.setNotifyUrl(w.getPayNotifyUrl());
                config.setHttpConnectionTimeout(w.getConnectTimeoutMs());
                config.setHttpTimeout(w.getReadTimeoutMs());
                WxPayService service = new WxPayServiceImpl();
                service.setConfig(config);
                payService = service;
                log.info("[TRADE] 微信支付客户端已初始化 mchId={}", w.getMchId());
            }
            return payService;
        }
    }

    /** APIv3 的时间是 RFC 3339；解析不了就返回 null，让调用方用本地时刻兜底。 */
    private static LocalDateTime parseTime(String rfc3339) {
        if (!StringUtils.hasText(rfc3339)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(rfc3339).toLocalDateTime();
        } catch (DateTimeParseException e) {
            log.warn("[TRADE] 无法解析渠道时间 {}", rfc3339);
            return null;
        }
    }
}
