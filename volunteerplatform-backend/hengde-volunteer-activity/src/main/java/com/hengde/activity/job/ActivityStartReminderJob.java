package com.hengde.activity.job;

import com.hengde.activity.config.ActivityProperties;
import com.hengde.activity.service.ActivityReminderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 「活动即将开始」提醒的<b>触发层</b>：判开关、调服务、记日志，不含业务逻辑。
 *
 * <p>形态与 honor 的 {@code RankingSnapshotJob} 一致，理由也一样：调度总开关
 * {@code @EnableScheduling} 在 api 模块，领域模块只提供带 {@code @Scheduled} 的 bean，
 * 这样领域模块的测试上下文不会起调度线程、半夜自己醒来改数据。
 * 将来接 XXL-Job 时，在本类旁边加一个 {@code @XxlJob} 处理器调同一个方法即可，业务代码不动。</p>
 *
 * <p><b>每小时跑一次</b>：提前量默认 24 小时，扫描窗口远大于执行间隔，
 * 因此漏跑一两轮不会让谁收不到——只要那一场还没开始，下一轮照样扫得到。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class ActivityStartReminderJob {

    private ActivityReminderService activityReminderService;
    private ActivityProperties activityProperties;

    @Autowired
    public void setActivityReminderService(ActivityReminderService activityReminderService) {
        this.activityReminderService = activityReminderService;
    }

    @Autowired
    public void setActivityProperties(ActivityProperties activityProperties) {
        this.activityProperties = activityProperties;
    }

    /** 默认每小时的第 5 分钟跑一次。 */
    @Scheduled(cron = "${hengde.activity.reminder.cron:0 5 * * * ?}")
    public void sendDueReminders() {
        if (!activityProperties.getReminder().isEnabled()) {
            return;
        }
        try {
            int reminded = activityReminderService.sendDueReminders();
            if (reminded > 0) {
                log.info("活动开始提醒完成，本轮提醒 {} 个场次", reminded);
            }
        } catch (Exception e) {
            // 定时任务不能把异常抛给调度器：抛出去只会在调度线程里留下一条无主的栈，
            // 丢掉「哪个任务、什么配置」这些排查时真正需要的上下文
            log.error("活动开始提醒任务执行失败", e);
        }
    }
}
