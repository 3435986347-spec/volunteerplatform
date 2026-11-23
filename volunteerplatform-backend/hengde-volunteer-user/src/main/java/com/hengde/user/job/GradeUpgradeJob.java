package com.hengde.user.job;

import com.hengde.user.config.GradeUpgradeProperties;
import com.hengde.user.service.GradeUpgradeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 年级升级的触发层：只判开关、调服务、记日志（业务在 {@link GradeUpgradeService}）。调度总开关在 api 的 {@code AsyncConfig}。
 *
 * @author hengde
 */
@Slf4j
@Component
public class GradeUpgradeJob {

    private GradeUpgradeService gradeUpgradeService;
    private GradeUpgradeProperties properties;

    @Autowired
    public void setGradeUpgradeService(GradeUpgradeService gradeUpgradeService) {
        this.gradeUpgradeService = gradeUpgradeService;
    }

    @Autowired
    public void setProperties(GradeUpgradeProperties properties) {
        this.properties = properties;
    }

    @Scheduled(cron = "${hengde.user.grade-upgrade.cron:0 20 3 * * ?}")
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            GradeUpgradeService.Result r = gradeUpgradeService.run(LocalDate.now());
            if (r.baselined() + r.upgraded() + r.prompted() > 0) {
                log.info("[GRADE] {} 学年：补基线 {} 人，升一级 {} 人，提示改学校和年级 {} 人",
                        r.schoolYear(), r.baselined(), r.upgraded(), r.prompted());
            }
        } catch (RuntimeException e) {
            log.error("[GRADE] 年级升级失败，明天会再试", e);
        }
    }
}
