package com.hengde.honor.service;

import com.hengde.activity.event.AttendanceConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 秘书部确认考勤 → 自动创建电子证书权益记录。
 *
 * <p>需求原文 xlsx Row 36 C：「参加完活动后，<b>自动生成一个盖章的电子证书</b>」。</p>
 *
 * <p><b>为什么单独成一个组件、而不是把 {@code @TransactionalEventListener} 放在
 * {@link CertificateService} 上</b>：那样监听方法与它要调的 {@code createForSlot} 在同一个类里，
 * 属<b>自调用</b>，Spring 的事务代理拦不到，{@code createForSlot} 上的 {@code @Transactional}
 * 会静默失效。拆开后调用跨过了代理边界，事务才真正生效。</p>
 *
 * @author hengde
 */
@Component
public class CertificateAutoCreateListener {

    private static final Logger log = LoggerFactory.getLogger(CertificateAutoCreateListener.class);

    private CertificateService certificateService;

    @Autowired
    public void setCertificateService(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    /**
     * <p><b>{@code AFTER_COMMIT}</b>：确认动作本身是一个事务。若在提交前建证书，
     * 确认一旦回滚，证书却留下了——志愿者会看到一张不该存在的证书。</p>
     *
     * <p><b>异常只记日志、不外抛</b>：这是确认动作的下游附带效果，且事务已经提交，
     * 此时抛异常既回滚不了确认、又会把一个已成功的操作显示成失败。</p>
     *
     * <p>⚠️ <b>但「只记日志」本身不构成兜底</b>：事件是进程内、不持久的，而
     * {@code secretaryConfirm} 的 CAS 一次性（{@code 0 → 1}）、<b>不会再触发第二次</b>，
     * 于是这里一旦失败，那位志愿者就永久没有证书、且没人会发现。
     * 真正的兜底是 {@link com.hengde.honor.job.CertificateReconcileJob}——
     * 它定期扫「已确认考勤却无证书」的并补上。<b>本监听器只负责「快」，不负责「不丢」。</b></p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAttendanceConfirmed(AttendanceConfirmedEvent event) {
        if (event.slotId() == null || event.activityId() == null || event.volunteerId() == null) {
            log.warn("考勤确认事件缺少归属信息，跳过自动建证书 event={}", event);
            return;
        }
        try {
            certificateService.createForSlot(event.volunteerId(), event.activityId(), event.slotId());
        } catch (Exception e) {
            log.error("自动创建电子证书失败 volunteerId={} activityId={} slotId={}",
                    event.volunteerId(), event.activityId(), event.slotId(), e);
        }
    }
}
