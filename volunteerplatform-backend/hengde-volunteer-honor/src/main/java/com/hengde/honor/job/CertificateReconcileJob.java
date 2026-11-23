package com.hengde.honor.job;

import com.hengde.activity.service.ActivityCertificateQueryService;
import com.hengde.honor.config.HonorProperties;
import com.hengde.honor.service.CertificateService;
import com.hengde.honor.vo.CertificateReconcileVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 证书补偿扫描：把「已确认考勤却没有证书」的补上。
 *
 * <p><b>为什么必须有它</b>：自动建证书挂在 {@code AttendanceConfirmedEvent} 上，而事件是
 * <b>进程内、不持久</b>的——监听器抛异常、应用在提交后崩溃、消息处理线程被打断，
 * 证书就永远不会被创建。而 {@code secretaryConfirm} 的 CAS 是一次性的
 * （{@code secretary_status: 0 → 1}），<b>不可能再触发第二次</b>，
 * 于是那位志愿者永久拿不到证书，且没有任何人会发现。
 * 原先只在监听器里 {@code catch + log}，等于把一次失败变成一条无人读的日志。</p>
 *
 * <p><b>本任务是唯一的自动最终一致性保证</b>：事件负责「快」，扫描负责「不丢」。
 * 由 {@code uk_slot_cert} 保证重复扫描不会多发；{@code createForSlot} 本身幂等。</p>
 *
 * <p>顺带覆盖另一条路径：<b>活动补录</b>落的考勤直接就是已确认态，
 * 即便它的事件也丢了，这里同样会补上。</p>
 *
 * <p><b>自动的那一层有窗口，因此必须配一个人工入口</b>：定时补偿只看回看窗口
 * （默认 72 小时）内确认的考勤，一旦「事件丢失 + 停机超过窗口」叠加，那张证书就再也补不回来。
 * {@link #reconcile(java.time.LocalDateTime, Long)} 是这个洞的救济，
 * 由 {@code POST /a/honor/certificates/reconcile} 暴露给管理员。</p>
 *
 * @author hengde
 */
@Component
public class CertificateReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(CertificateReconcileJob.class);

    private ActivityCertificateQueryService activityQueryService;
    private CertificateService certificateService;
    private HonorProperties honorProperties;

    @Autowired
    public void setActivityQueryService(ActivityCertificateQueryService activityQueryService) {
        this.activityQueryService = activityQueryService;
    }

    @Autowired
    public void setCertificateService(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    @Autowired
    public void setHonorProperties(HonorProperties honorProperties) {
        this.honorProperties = honorProperties;
    }

    /** 定时入口。{@code @EnableScheduling} 的总开关在 api 模块，测试上下文不会起线程。 */
    @Scheduled(cron = "${hengde.honor.certificate.reconcile-cron:0 15 * * * ?}")
    public void scheduled() {
        if (!honorProperties.getCertificate().isReconcileEnabled()) {
            return;
        }
        CertificateReconcileVO result = reconcile();
        if (result.getCreated() > 0) {
            log.warn("证书补偿扫描补建了 {} 张——说明有事件在投递或处理时丢了，值得查日志",
                    result.getCreated());
        }
        if (result.isHasMore()) {
            // 定时任务撞上限不影响正确性（下一轮接着补），但它意味着一小时内积压了上千张缺失，
            // 那不是「偶尔丢一条事件」该有的量级，值得当成告警看。
            log.warn("证书补偿扫描达到单次上限仍未补完——积压量级异常，请检查事件链路");
        }
        if (result.getFailed() > 0) {
            log.error("证书补偿扫描有 {} 条补建失败——定时任务下一轮会重试，但连续多轮都有说明是数据问题",
                    result.getFailed());
        }
    }

    /**
     * 定时补偿：只扫<b>回看窗口内</b>确认的考勤。
     *
     * <p>只补最近这段时间内确认的考勤。没有下界会从库里第一条已确认考勤扫起，
     * 第一次跑就给所有历史活动批量发证，而那条口径协会还没答复。</p>
     */
    public CertificateReconcileVO reconcile() {
        java.time.LocalDateTime since = java.time.LocalDateTime.now()
                .minusHours(honorProperties.getCertificate().getReconcileLookbackHours());
        return reconcile(since, null);
    }

    /**
     * 扫一遍已确认考勤，缺证书的补上。
     *
     * <p><b>为什么要有可指定范围的这一版</b>：定时补偿被回看窗口限在 72 小时内，
     * 窗口本身就是个洞——事件丢失叠加应用停机（或补偿被临时关掉）超过窗口，
     * 那张证书就永久缺失、无人发现，且<b>没有任何入口能补</b>。
     * 本方法是那个洞的救济：范围由人显式给出（一段时间，或某个活动），不由 cron 决定。</p>
     *
     * <p>⚠️ <b>它不是「历史活动补发工具」</b>。协会 2026-08-11 已定：只对系统上线后开展的活动
     * 自动发证，历史活动不补发（且现存历史活动全部是测试数据）。</p>
     *
     * <p>按考勤 id 递增翻页而<b>不用 offset</b>：补偿任务会反复跑，
     * offset 分页在并发写入下会漏行。</p>
     *
     * <p><b>翻页的实测代价（2026-07-31，30 万行库）</b>：EXPLAIN 在<b>每个</b>游标位置都是
     * {@code rows≈4285 / Using filesort}——索引给的是 {@code (status, time, id)} 序而查询要
     * {@code ORDER BY id}，故每批都把整个窗口扫一遍排一次序、再丢掉 ≤ cursor 的部分，
     * 每轮总成本约 O(窗口² / batch)。窗口 4300 行时是 9 批 × 4300，可忽略；
     * <b>只有当某个大活动日把 72h 窗口顶到几万行时才会明显</b>。
     * 真要改是把游标换成 {@code (secretary_time, id)} 复合游标——索引就能同时供过滤与排序，
     * filesort 消失。<b>当前刻意不改</b>，数字记在这里免得将来变慢时重新测一遍。</p>
     *
     * @param since      只补这个时刻之后确认的考勤；可为 null（此时必须给 activityId）
     * @param activityId 只补这个活动下的考勤；可为 null（此时必须给 since）
     */
    public CertificateReconcileVO reconcile(java.time.LocalDateTime since, Long activityId) {
        if (since == null && activityId == null) {
            // 【这道守卫买到的是「防误触」，不是「防规模」】——别误解它的作用范围：
            // since 填 1970-01-01 同样通过守卫、效果与全量补发完全一致，这是刻意允许的。
            // 人显式敲下 1970 是他的决定；cron 不声不响地把历史证书全发出去不是。
            // 真正约束规模的是下面的单次上限 + hasMore。
            throw new com.hengde.common.exception.BusinessException(
                    "补发范围必须指定：至少给出起始确认时间或活动 id");
        }
        // 刻意【不看】reconcile-enabled：那个开关关的是「定时任务自动跑」，
        // 而管理员显式点补发时，正常的处境恰恰是「排障期临时关掉了定时任务」。
        // 让开关连人工入口一起堵死，等于在最需要它的时候把救济也关了。
        var cfg = honorProperties.getCertificate();
        int batchSize = cfg.getReconcileBatchSize();
        int maxCreates = cfg.getReconcileMaxCreatesPerRun();
        long cursor = 0L;
        int created = 0;
        int failed = 0;
        boolean hasMore = false;
        while (true) {
            List<ActivityCertificateQueryService.AttendanceRef> batch =
                    activityQueryService.listConfirmedAfter(since, activityId, cursor, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            // 一批一次查询问出「哪些已经有证书」，再做差集。
            // 逐行 createIfAbsent 会让每轮变成 N 次 select，考勤表一大就慢得没法按小时跑。
            var existing = certificateService.existingKeys(batch.stream()
                    .map(ActivityCertificateQueryService.AttendanceRef::slotId)
                    .filter(java.util.Objects::nonNull).distinct().toList());
            for (var s : batch) {
                if (created >= maxCreates) {
                    // 达到单次上限：提前收尾并如实回报「还没补完」。
                    // 剩下的靠【下次调用从头重扫 + 批量差集跳过已有】补上，而不是靠续传游标——
                    // 游标是方法内的局部量，本就不跨调用保留。这也正是不需要任务 id 的原因：
                    // 每次调用都是一次完整的、幂等的「把缺的补到上限为止」。
                    hasMore = true;
                    break;
                }
                cursor = Math.max(cursor, s.attendanceId());
                if (s.slotId() == null || s.activityId() == null || s.volunteerId() == null) {
                    continue;
                }
                if (existing.contains(CertificateService.subjectKey(s.volunteerId(), s.slotId()))) {
                    continue;
                }
                try {
                    if (certificateService.createIfAbsent(s.volunteerId(), s.activityId(), s.slotId())) {
                        created++;
                    }
                } catch (Exception e) {
                    // 单条失败不能让整轮停下——否则一条坏数据会挡住它后面所有人的证书。
                    // 但【必须计数并回报】：只记日志的话，接口会回 hasMore=false 报「补完了」，
                    // 而人工补发要回答的唯一问题就是「补完了没有」。
                    failed++;
                    log.error("证书补偿单条失败 attendanceId={} volunteerId={} slotId={}",
                            s.attendanceId(), s.volunteerId(), s.slotId(), e);
                }
            }
            if (hasMore || batch.size() < batchSize) {
                break;
            }
        }
        CertificateReconcileVO vo = new CertificateReconcileVO();
        vo.setCreated(created);
        vo.setFailed(failed);
        vo.setHasMore(hasMore);
        return vo;
    }
}
