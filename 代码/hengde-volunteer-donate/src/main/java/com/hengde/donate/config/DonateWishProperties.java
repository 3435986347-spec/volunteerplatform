package com.hengde.donate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微心愿配置，绑定 {@code hengde.donate.wish.*}。
 *
 * <p><b>物资接收地址是一个配置、不是每个心愿一份</b>：Row 12 C「捐赠人需要按要求把物资寄到办公室」——
 * 收件的永远是协会办公室，心愿只决定寄什么。与商城自提点、纸质证书领取点同一形状（运营常量走配置）。
 * 认领人在心愿详情里看到它，并可「快速复制」（前端的事）。</p>
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.donate.wish")
public class DonateWishProperties {

    /** 收件人（如「恒德协会 微心愿组」）。 */
    private String recvName;

    /** 收件电话。 */
    private String recvPhone;

    /** 收件地址。 */
    private String recvAddress;
}
