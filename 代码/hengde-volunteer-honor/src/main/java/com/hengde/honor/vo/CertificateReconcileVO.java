package com.hengde.honor.vo;

import lombok.Data;

/**
 * 证书补发的执行结果。
 *
 * <p><b>为什么不能只回一个数字</b>：本接口的定位就是「协会答复历史要补发时的执行工具」，
 * 天然要处理大批量，而网关 {@code proxy_read_timeout} 是 60 秒（见 `部署/nginx.conf`）——
 * 一次几千张的补发会在网关 504，而服务端还在继续跑：管理员看到的是失败、拿不到张数，
 * 很自然会再点一次，于是两轮重叠（靠 {@code uk_slot_cert} 不会发重，但白烧一遍）。
 * 故单次调用<b>封顶</b>，用 {@link #hasMore} 告诉调用方「还没补完，请再点一次」。</p>
 *
 * <p><b>为什么不引入异步任务与任务 id</b>：补发本身<b>幂等</b>，
 * 「重复点直到返回 0」本来就是正确用法。为此加一套任务表 + 轮询接口，
 * 买到的东西不比「再点一次」多，却多出一份要维护的状态。</p>
 *
 * @author hengde
 */
@Data
public class CertificateReconcileVO {

    /** 本次实际补建的证书张数 */
    private int created;

    /**
     * 本次<b>逐条失败</b>的条数（已记 error 日志，不影响其余条目）。
     *
     * <p><b>为什么必须回报它</b>：补发是逐条 catch 的——单条失败不能让整轮停下，
     * 否则一条坏数据会挡住它后面所有人的证书。但只报 {@code created} 的话，
     * 失败就只剩一行没人读的日志，而接口回 {@code hasMore=false} 等于告诉管理员「补完了」。
     * 人工补发要回答的唯一问题恰恰是「这一批补完了没有」，
     * 它却正好在出事的时候把这个问题答错——与本批一直在修的
     * 「报告成功、实际没做」是同一形状。</p>
     *
     * <p><b>「真的补完了」的判据是 {@code hasMore=false 且 failed=0}</b>，不是只看前者。</p>
     */
    private int failed;

    /** 是否还有未补完的（达到单次上限而提前收尾）——为 true 时请再调一次 */
    private boolean hasMore;
}
