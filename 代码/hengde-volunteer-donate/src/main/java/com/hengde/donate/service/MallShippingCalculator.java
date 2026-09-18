package com.hengde.donate.service;

import com.hengde.common.exception.BusinessException;
import com.hengde.donate.config.MallProperties;
import com.hengde.donate.constant.MallDeliveryType;
import com.hengde.donate.entity.MallGoods;
import com.hengde.donate.entity.MallGoodsSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 快递费计价器（商城快递批）。
 *
 * <p><b>应付金额是计价器的输出，不是常量</b>——V2 第 4 批（纸质证书快递费）定下的口径，V3 商城接进来时只换计价器。
 * 当前实现返回配置里的一个固定值；协会日后要按地区、重量或商品计价，改的只是这一个类，
 * 订单上的快照列（快递费、汇率、折算积分）不用动。</p>
 *
 * <p>计价的输入里<b>故意带着商品与规格</b>，哪怕现在用不上：将来「某些商品包邮」「大件另算」都要它们，
 * 到时候不必改调用方。</p>
 *
 * @author hengde
 */
@Component
public class MallShippingCalculator {

    private MallProperties properties;

    @Autowired
    public void setProperties(MallProperties properties) {
        this.properties = properties;
    }

    /** 快递是否开放。 */
    public boolean expressEnabled() {
        return properties.getExpress().isEnabled();
    }

    /**
     * 快递费【分】。自提为 0。
     *
     * @throws BusinessException 快递未开放
     */
    public int feeFen(int deliveryType, MallGoods goods, MallGoodsSpec spec) {
        if (deliveryType != MallDeliveryType.EXPRESS) {
            return 0;
        }
        if (!expressEnabled()) {
            throw new BusinessException("暂不支持快递寄送，请选择自提");
        }
        return Math.max(0, properties.getExpress().getFeeFen());
    }

    /** 当前汇率：1 元折多少积分。 */
    public int pointsPerYuan() {
        return Math.max(1, properties.getExpress().getPointsPerYuan());
    }

    /**
     * 快递费折成积分：⌈fee（分）× 汇率 / 100⌉，<b>向上取整</b>——向下取整会让差几分钱的快递费白送。
     */
    public static int pointsFor(int feeFen, int pointsPerYuan) {
        if (feeFen <= 0) {
            return 0;
        }
        long numerator = (long) feeFen * pointsPerYuan;
        long points = (numerator + 99) / 100;
        if (points > Integer.MAX_VALUE) {
            throw new BusinessException("快递费折算积分溢出，请检查汇率配置");
        }
        return (int) points;
    }
}
