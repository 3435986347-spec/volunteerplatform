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

    /** 快递寄送（商城快递批）。 */
    private Express express = new Express();

    /** 现金付款（商城快递批）：有现金部分的单下单后多久内要付完。 */
    private Payment payment = new Payment();

    /**
     * 快递寄送。<b>默认关闭</b>：快递费多少、积分怎么折，协会都还没给（《协会待确认清单-v3》③）。
     *
     * <p>快递费是<b>计价器的输出</b>（{@code MallShippingCalculator}），当前计价器只返回 {@link #feeFen}——
     * 协会日后要按地区 / 重量计价时换计价器，订单上的快照列不用改。两个数都在<b>下单时快照进订单</b>，
     * 调价不改写历史单据。</p>
     */
    @Data
    public static class Express {

        private boolean enabled = false;

        /** 快递费【分】。 */
        private int feeFen = 1000;

        /**
         * 积分抵扣快递费的汇率：<b>1 元折多少积分</b>。抵扣所需积分 = ⌈快递费（元）× 汇率⌉，向上取整——
         * 向下取整会让「差几分钱的快递费」白送。
         */
        private int pointsPerYuan = 100;

        /** 发货后满这么多天仍未确认收货的，系统自动确认（否则一张忘了点的单永远停在已发货、也评价不了）。 */
        private int autoReceiveDays = 15;
    }

    @Data
    public static class Payment {

        /**
         * 付款时限（分钟）。<b>待支付的单占着库存、积分与卷</b>，不设时限就等于一个人能把一件商品无限期锁住。
         * 这个截止时刻同时传给 trade，交易单不会比兑换单活得久。
         */
        private int timeoutMinutes = 15;
    }

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
