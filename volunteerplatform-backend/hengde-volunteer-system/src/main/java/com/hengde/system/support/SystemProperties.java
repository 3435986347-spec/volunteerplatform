package com.hengde.system.support;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 系统治理域的配置（V4 系统治理批）。
 *
 * <p><b>默认值写在这里而不只写在 api 的 yaml</b>：yaml 只在 api 上下文生效，领域测试读不到，
 * 「保留 180 天」就成了只在部署里成立的话（trade 批的 TTL 记过这一课）。</p>
 *
 * @author hengde
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "hengde.system")
public class SystemProperties {

    /** 操作日志保留多少天（V4规划 Q8 默认 180） */
    private int logRetentionDays = 180;

    /** 一次清理最多删多少行（分批删，避免一条 DELETE 锁太久） */
    private int logPurgeBatch = 2000;

    /** 内存里最多攒多少条待落库的日志——满了就丢并记一行 WARN：日志绝不能把业务拖垮 */
    private int logQueueCapacity = 5000;

    /** 每次落库最多写多少条 */
    private int logFlushBatch = 200;

    /** 分享链接默认有效期（小时）；0 = 不过期 */
    private int shareDefaultHours = 168;
}
