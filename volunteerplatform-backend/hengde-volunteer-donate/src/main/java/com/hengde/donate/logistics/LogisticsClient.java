package com.hengde.donate.logistics;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 物流轨迹查询的端口。生产实现是 {@link Kuaidi100LogisticsClient}；测试里用一个假的替换它，
 * 这样轮询、快照、终态停止这些<b>我们自己的逻辑</b>可以在没有快递100 账号时被完整测试。
 *
 * @author hengde
 */
public interface LogisticsClient {

    /** 是否开通（有账号且配置齐全）。未开通时调用方不应发起查询。 */
    boolean enabled();

    /**
     * 查询一张运单的轨迹。
     *
     * @param companyCode 快递100 公司编码
     * @param expressNo   单号
     * @param phone       收 / 寄件人手机号（顺丰等要求），可空
     * @return 查询结果；「查无结果（还没揽收）」是正常结果，节点为空
     * @throws LogisticsException 签名错误、服务繁忙、网络失败等——调用方记下查询时间、保留旧快照
     */
    TrackResult query(String companyCode, String expressNo, String phone);

    /** 一次查询的结果。{@code state} 为快递100 的物流状态码；{@code nodes} 新的在前。 */
    record TrackResult(Integer state, List<TrackNode> nodes, String rawJson) {

        public TrackNode latest() {
            return nodes == null || nodes.isEmpty() ? null : nodes.get(0);
        }
    }

    /** 轨迹节点。 */
    record TrackNode(LocalDateTime time, String context) {
    }

    /** 查询失败（与「查无结果」区分开）。 */
    class LogisticsException extends RuntimeException {
        public LogisticsException(String message) {
            super(message);
        }

        public LogisticsException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
