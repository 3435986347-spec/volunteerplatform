package com.hengde.donate.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 积分商城配置，绑定 {@code hengde.donate.mall.*}。
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.donate.mall")
public class MallProperties {

    /** 自提点。 */
    private PickupSite pickupSite = new PickupSite();

    /**
     * 自提点——<b>配置项 + 下单快照，不建自提点表</b>。
     *
     * <p><b>为什么是配置而不是一张表</b>：Row 8 C 写「志愿者确认领取后需要给他选择兑换地址」，
     * 「选择」暗示多个；但 V2 已为纸质证书判定不建自提点表（来源里只出现过一个固定值
     * P86「领取地址：协会办公室」）。两处必须同形，故取同一形状。
     * 「一个还是多个」见《协会待确认清单-v3》⑪。</p>
     *
     * <p><b>为什么审核通过时要把它快照进订单的三列文本</b>：配置只有一个<b>当前</b>值。
     * 协会换了办公地点，历史单据仍要还原得出「当时让他去哪儿领」。
     * 这与订单的商品名/规格名/积分三项快照是同一条理由。</p>
     *
     * <p>协会日后答「多个」时，<b>加一张表即可，订单上那三列不用改</b>——
     * 这正是快照设计买到的东西。</p>
     */
    @Data
    public static class PickupSite {

        /** 名称，如「协会办公室」。 */
        private String name;

        /** 地址。 */
        private String address;

        /** 联系电话。 */
        private String phone;
    }
}
