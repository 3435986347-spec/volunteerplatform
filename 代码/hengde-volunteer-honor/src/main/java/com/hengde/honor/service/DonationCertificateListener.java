package com.hengde.honor.service;

import com.hengde.donate.event.PairCancelledEvent;
import com.hengde.donate.event.PairEstablishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 结对成立 → 自动创建捐赠证书；结对被取消 → 撤销那张证书（xlsx Row 10「捐赠后需要自动生成证书」）。
 *
 * <p><b>为什么单独成一个组件</b>：与 {@link CertificateAutoCreateListener} 同一理由——
 * 监听方法若与它要调的 {@code createForDonation} 同处 {@link CertificateService}，属自调用，
 * Spring 的事务代理拦不到，那个方法上的 {@code @Transactional} 会静默失效。</p>
 *
 * <p><b>{@code AFTER_COMMIT}</b>：确认成立本身是一个事务，提交前就出证的话，成立一旦回滚，
 * 证书却留下了——结对人会拿到一张为不存在的结对背书的盖章证书。</p>
 *
 * <p>⚠️ <b>「只记日志」不构成兜底</b>：事件是进程内、不持久的，而「确认成立」的 CAS 一次性（0 → 1）、
 * 不会再触发第二次。活动证书那条线有 {@link com.hengde.honor.job.CertificateReconcileJob} 定期补，
 * <b>捐赠证书本批没有定时补偿</b>——人工救济是
 * {@code POST /a/honor/certificates/pairs/{pairRecordId}}（幂等，见 {@code AdminCertificateController}）。
 * 之所以不顺手加一个定时任务：它要扫的是 donate 的结对表，扫描口径、回看窗口与「历史数据要不要补」
 * 都得先有协会口径，而那正是活动证书那边吃过亏的地方（回看窗口不设下界会第一次跑就给全历史发证）。</p>
 *
 * @author hengde
 */
@Component
public class DonationCertificateListener {

    private static final Logger log = LoggerFactory.getLogger(DonationCertificateListener.class);

    private CertificateService certificateService;

    @Autowired
    public void setCertificateService(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    /** 结对成立：出证。异常只记日志——事务已提交，此时抛异常既回滚不了成立、又会把成功显示成失败。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPairEstablished(PairEstablishedEvent event) {
        if (event.pairRecordId() == null) {
            log.warn("结对成立事件缺少登记 id，跳过自动出证 event={}", event);
            return;
        }
        try {
            certificateService.createForDonation(event.pairRecordId());
        } catch (Exception e) {
            log.error("自动创建捐赠证书失败 pairRecordId={} volunteerId={}",
                    event.pairRecordId(), event.volunteerId(), e);
        }
    }

    /**
     * 结对被取消：撤销证书（软删，留痕可恢复）。
     *
     * <p>取消有可能是误操作，所以是软删不是物删——V31 的软删口径本就支持「恢复原记录、保留原编号与文件」。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPairCancelled(PairCancelledEvent event) {
        if (event.pairRecordId() == null) {
            return;
        }
        try {
            certificateService.revokeForPair(event.pairRecordId(), event.reason());
        } catch (Exception e) {
            log.error("撤销捐赠证书失败 pairRecordId={} volunteerId={}",
                    event.pairRecordId(), event.volunteerId(), e);
        }
    }
}
