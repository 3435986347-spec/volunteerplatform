package com.hengde.donate.logistics;

/**
 * 物流<b>订阅推送</b>的端口（V3 物流推送批）。与查询端口 {@link LogisticsClient} 分开：
 * 两者在快递100 是两个计费项（《协会待确认清单-v3》⑧），开没开通、配置要什么都不一样。
 *
 * <p>生产实现是 {@link Kuaidi100PushClient}；测试里换成可编程的假实现，
 * 「只订一次、失败重试、放弃后交还轮询、回调验签与乱序」这些<b>我们自己的逻辑</b>才能在没有账号时被测到。</p>
 *
 * @author hengde
 */
public interface LogisticsPushClient {

    /** 是否开通（开关打开且授权 key 与回调地址齐全）。 */
    boolean enabled();

    /**
     * 订阅一张运单。
     *
     * @param callbackUrl 带运单 id 的完整回调地址
     * @param salt        回调验签用的 salt（快递100 推送时以 MD5(param + salt) 签名）
     * @return 受理结果；{@code accepted=false} 时 {@code permanent} 表示「再试也没用」（快递公司 / 单号被拒）
     * @throws LogisticsClient.LogisticsException 网络失败、服务繁忙等——调用方按可重试处理
     */
    SubscribeResult subscribe(String companyCode, String expressNo, String phone, String callbackUrl, String salt);

    /**
     * 订阅受理结果。
     *
     * @param accepted  已受理（含「重复订阅」——之前那次其实订上了，只是应答没收到）
     * @param permanent 未受理且再试也没用
     * @param code      快递100 返回码
     * @param message   快递100 返回的说明
     */
    record SubscribeResult(boolean accepted, boolean permanent, String code, String message) {
    }
}
