package com.hengde.honor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * honor 域可配置项（前缀 {@code hengde.honor}）。
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.honor")
public class HonorProperties {

    /** 排行榜相关 */
    private Ranking ranking = new Ranking();

    /** 证书相关（第 4 批） */
    private Certificate certificate = new Certificate();

    /** 奖惩中心相关（第 5 批） */
    private RewardPunish rewardPunish = new RewardPunish();

    /** 本类是 {@code @Component}，校验挂这里才真正会跑。 */
    @jakarta.annotation.PostConstruct
    public void validateAll() {
        certificate.validate();
        rewardPunish.validate();
    }

    /** 奖惩中心配置。 */
    @Data
    public static class RewardPunish {

        /**
         * 申诉期天数（默认 7）。
         *
         * <p><b>需求原文</b>：xlsx Row 41 F「审核之后，志愿者会收到提示，并有<b>7天申诉期</b>」。
         * 做成配置只是为了协会日后想调；默认值就是原文那个 7。</p>
         *
         * <p><b>改它不影响在途的处罚</b>：截止时刻在审核通过那一刻就写进
         * {@code honor_reward_punish.appeal_deadline}，此后不再重算——
         * 否则把 7 改成 3 会追溯性地缩短甚至当场作废别人已经在走的申诉权。</p>
         */
        private int appealDays = 7;

        /** 配错就起不来，而不是静默按 0 天算（那等于取消申诉权）。 */
        public void validate() {
            if (appealDays <= 0) {
                throw new IllegalStateException(
                        "hengde.honor.reward-punish.appeal-days 必须 > 0，当前为 " + appealDays
                                + "；配成 0 等于取消 Row 41 F 要求的申诉期");
            }
        }
    }

    /** 证书配置。 */
    @Data
    public static class Certificate {

        /**
         * 下载签名 URL 的有效期（秒，默认 120）。
         *
         * <p><b>不能设得太长</b>：签名 URL 在有效期内等同凭证，任何拿到链接的人都能取走证书。
         * 上限还受 {@code hengde.oss.presign-max-ttl-seconds}（默认 300）二次约束，超限直接拒签。</p>
         */
        private int downloadUrlTtlSeconds = 120;

        /** 证书 PDF 在对象存储中的目录前缀 */
        private String objectDir = "certificate";

        /**
         * 渲染时的分布式锁等待秒数（默认 5）。
         *
         * <p>同一张证书被并发下载时只让一个线程去渲染，其余等它写完直接复用文件。
         * 等不到就报错让用户重试，而不是各渲染各的——那既白烧 CPU 又会互相覆盖。</p>
         */
        private int renderLockWaitSeconds = 5;

        /**
         * 是否启用「证书补偿扫描」（默认开）。
         *
         * <p>自动建证书挂在进程内事件上，事件不持久：监听器抛异常或应用在事务提交后崩溃，
         * 证书就永远建不出来，而秘书部确认的 CAS 是一次性的、不会再触发。
         * <b>关掉它就等于接受「偶尔有人永久拿不到证书且无人发现」</b>，仅建议排障时临时关。</p>
         */
        private boolean reconcileEnabled = true;

        /** 补偿扫描的 cron（默认每小时第 15 分钟） */
        private String reconcileCron = "0 15 * * * ?";

        /**
         * 补偿扫描每批扫多少条考勤（默认 500）。
         *
         * <p><b>必须 &gt; 0</b>：配成 0 或负数会让每批查回空列表、循环立刻退出，
         * 于是「唯一的最终一致性保证」被<b>静默关掉</b>，日志上还一切正常。
         * 故启动时校验非法值直接报错，而不是当成「不限」或「默认值」。</p>
         */
        private int reconcileBatchSize = 500;

        /**
         * 补偿扫描的<b>回看窗口</b>（小时，默认 72）。
         *
         * <p>只补「最近这段时间内被秘书部确认」的考勤。<b>不能不限</b>——
         * 不加下界会从库里第一条已确认考勤开始扫，补偿任务第一次跑就给
         * <b>所有历史活动</b>批量发证，而「改造前已结束的活动是否补发证书」
         * 协会<b>尚未答复</b>，等于让定时任务替协会把待决问题答了，且发出去收不回来。</p>
         *
         * <p>补偿要解决的是「刚刚那条事件丢了」，72 小时足以覆盖一次应用崩溃 + 人工发现的周期；
         * 真要给历史数据补发，应当是一次<b>显式的、有协会口径的</b>批量操作，不是定时任务的副作用。</p>
         */
        private int reconcileLookbackHours = 72;

        /**
         * 单次补发最多补建多少张（默认 1000）。
         *
         * <p><b>这条约束来自网关而不是业务</b>：人工补发接口是同步执行的，
         * 而 nginx 的 {@code proxy_read_timeout} 是 60 秒。一次几千张的补发会在网关 504，
         * 服务端却还在跑——管理员看到失败、拿不到张数，很自然会再点一次，两轮重叠白烧一遍
         * （靠 {@code uk_slot_cert} 不会发重，但活儿白干）。</p>
         *
         * <p>达到上限就提前收尾并回 {@code hasMore=true}，让调用方再点一次。
         * 补发幂等，「重复点到返回 0」本就是正确用法，故不需要异步任务与任务 id。</p>
         */
        private int reconcileMaxCreatesPerRun = 1000;

        /**
         * 启动期校验：把「配错就静默失效」变成「配错就起不来」。
         *
         * <p><b>由外层 {@link HonorProperties#validateAll()} 调用</b>——本类不是 Spring bean，
         * 在这里挂 {@code @PostConstruct} 不会被触发，那种「看着有校验其实没有」比没有更坏。</p>
         */
        public void validate() {
            if (reconcileBatchSize <= 0) {
                throw new IllegalStateException(
                        "hengde.honor.certificate.reconcile-batch-size 必须 > 0，当前为 " + reconcileBatchSize
                                + "；配成 0 会静默关闭证书补偿扫描");
            }
            if (reconcileLookbackHours <= 0) {
                throw new IllegalStateException(
                        "hengde.honor.certificate.reconcile-lookback-hours 必须 > 0，当前为 "
                                + reconcileLookbackHours);
            }
            if (reconcileMaxCreatesPerRun <= 0) {
                throw new IllegalStateException(
                        "hengde.honor.certificate.reconcile-max-creates-per-run 必须 > 0，当前为 "
                                + reconcileMaxCreatesPerRun + "；配成 0 会让补发一张也补不出来");
            }
        }
    }

    /** 排行榜配置。 */
    @Data
    public static class Ranking {

        /**
         * 是否启用快照定时生成。
         *
         * <p>关掉后往期榜单会一直走实时聚合（名次随数据变动），仅建议在排障时临时关闭。</p>
         */
        private boolean snapshotEnabled = true;

        /**
         * 快照生成的 cron（默认每天 00:30）。
         *
         * <p>每天跑而不是「每月 1 日跑」：按月触发一旦当天服务不在线，那个月的快照就永久缺失了；
         * 每天跑配合「已存在则跳过」的幂等语义，漏跑会在次日自动补上。</p>
         */
        private String snapshotCron = "0 30 0 * * ?";

        /**
         * 周期结束后延迟多少天才冻结快照（默认 7 天）。
         *
         * <p><b>不能周期一结束就冻结</b>：时长榜依赖秘书部确认（{@code secretary_status=1}），
         * 积分依赖确认后发放，这些动作往往发生在活动结束后的几天里。月末 0 点就把名次定死，
         * 冻进去的会是一份还没结算完的残缺数据。留出冷静期让当月数据落定，期间榜单照常实时展示。</p>
         *
         * <p>该冷静期<b>只约束定时任务</b>；管理员在后台显式补跑不受它限制。</p>
         */
        private int freezeDelayDays = 7;

        /**
         * 每张榜单快照保留的名次数（默认 100）。
         *
         * <p>比查询默认页大一截：前端将来想把榜单从前 50 放宽到前 100 时，
         * 历史快照里得真有那些行，否则往期榜单只能停在 50 名。</p>
         */
        private int snapshotTopN = 100;
    }
}
