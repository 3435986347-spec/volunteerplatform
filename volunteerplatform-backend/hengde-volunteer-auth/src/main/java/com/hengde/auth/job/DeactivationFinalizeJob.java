package com.hengde.auth.job;

import com.hengde.auth.config.AuthProperties;
import com.hengde.auth.service.VolunteerDeactivationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 注销冷静期到期的<b>收尾</b>：把已过冷静期、还没人碰到的账号落定（清手机号、换 openid）。
 *
 * <p><b>它不是正确性的依据</b>：到没到期按时间现算，到期后第一次碰到这个账号（请求闸门、登录、按手机号查找）
 * 就当场落定——这个任务只是让那些再也没人碰的账号也把手机号释放出来、状态与实际一致。漏跑几轮无妨。</p>
 *
 * <p>形态与其它 {@code *Job} 一致：只判开关、调服务、记日志；{@code @EnableScheduling} 在 api 模块，
 * 领域模块的测试上下文不起调度线程。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class DeactivationFinalizeJob {

    private VolunteerDeactivationService deactivationService;
    private AuthProperties authProperties;

    @Autowired
    public void setDeactivationService(VolunteerDeactivationService deactivationService) {
        this.deactivationService = deactivationService;
    }

    @Autowired
    public void setAuthProperties(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    /** 默认每 10 分钟跑一次。 */
    @Scheduled(cron = "${hengde.auth.deactivation-cron:0 */10 * * * ?}")
    public void finalizeDue() {
        if (!authProperties.isDeactivationJobEnabled()) {
            return;
        }
        try {
            int n = deactivationService.finalizeAllDue();
            if (n > 0) {
                log.info("注销冷静期到期收尾：本轮落定 {} 个账号", n);
            }
        } catch (Exception e) {
            log.error("注销冷静期到期收尾任务执行失败", e);
        }
    }
}
