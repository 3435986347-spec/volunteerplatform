package com.hengde.organization.biz.listener;

import com.hengde.auth.event.VolunteerDeactivatedEvent;
import com.hengde.organization.biz.service.DeactivationCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 志愿者注销落定之后，让出他在组织架构 / 小组里的位置（{@link DeactivationCleanupService}）。
 *
 * <p>{@code fallbackExecution = true}：落定常常发生在请求闸门里（没有事务），缺了它监听器一次都不会被调用。
 * 在事务里落定的（如注册、换绑查重时顺手释放别人的号）等提交之后才收拾，回滚则什么都不发生。</p>
 *
 * <p>失败只记日志：注销已经落定，不能因为这一步失败而影响当前请求；由 {@code DeactivationCleanupJob} 兜底。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class VolunteerDeactivatedListener {

    private DeactivationCleanupService cleanupService;

    @Autowired
    public void setCleanupService(DeactivationCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeactivated(VolunteerDeactivatedEvent event) {
        try {
            cleanupService.cleanup(event.volunteerId());
        } catch (RuntimeException e) {
            log.error("[DEACTIVATE] 注销后让出组织位置失败 volunteerId={}，由定时任务兜底", event.volunteerId(), e);
        }
    }
}
