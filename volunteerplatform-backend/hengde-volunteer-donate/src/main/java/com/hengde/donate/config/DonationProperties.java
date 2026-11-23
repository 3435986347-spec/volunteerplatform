package com.hengde.donate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 捐款配置，绑定 {@code hengde.donate.donation.*}（V3 捐款批）。
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.donate.donation")
public class DonationProperties {

    /**
     * 付款时限（分钟）。捐款不占库存，给得比商城宽；截止时刻同时传给 trade。
     * 结对捐款待支付期间占着「这一条结对至多一笔待支付」的名额，所以也不能无限长。
     */
    private int payTimeoutMinutes = 30;

    /** 单笔捐款上限（元）。微信单笔支付本身有上限，超过的请走线下对公转账。 */
    private BigDecimal maxAmount = new BigDecimal("50000");
}
