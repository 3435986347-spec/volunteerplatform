package com.hengde.activity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * activity 域配置，绑定 {@code hengde.activity.*}。
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.activity")
public class ActivityProperties {

    /**
     * 「紧急上报 / 联系负责人」预设电话（前端 {@code tel:} 拨号，见原型 p92/p112）。
     * 按部署方配置 {@code hengde.activity.emergency-phone}；未配置则为 null，前端据此隐藏入口。
     */
    private String emergencyPhone;
}
