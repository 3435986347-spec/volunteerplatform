package com.hengde.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 年级每年 9 月自动升级（{@code hengde.user.grade-upgrade}，V4 个人中心补全批）。
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.user.grade-upgrade")
public class GradeUpgradeProperties {

    /** 关掉后任务什么也不做（排障用）。 */
    private boolean enabled = true;

    /**
     * 每天跑、不是每年 9 月 1 日跑：按学年幂等（{@code grade_upgrade_year < 当前学年} 才处理），
     * 那天服务不在线也能在之后补上，重复跑什么也不改。
     */
    private String cron = "0 20 3 * * ?";
}
