package com.hengde.organization;

import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.organization.service.SanctionPermissionAudit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动自检：能开奖惩单、却一个处置档位都开不了的后台账号要被点名。
 *
 * <p>V85 起三档各一个权限点，V86 / V92 按部门文字补授；部门文字对不上的账号会悄悄失去原本能开的档位，
 * 开单弹窗只是把那几档藏起来。{@link SanctionPermissionAudit} 把这些账号列出来（启动时打 WARN）。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class SanctionPermissionAuditTest {

    @Autowired
    private SanctionPermissionAudit audit;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void punishersWithoutAnySanctionScope_areNamed() {
        String tag = Long.toString(System.nanoTime(), 36);
        long typo = admin("audit-typo-" + tag, "组织部门");        // 部门写错：补授对不上
        long ok = admin("audit-ok-" + tag, "组织部");
        long disabled = admin("audit-off-" + tag, "组织部门");
        long plain = admin("audit-plain-" + tag, "组织部门");       // 没有开单权，不相干
        grant(typo, "honor:reward-punish");
        grant(ok, "honor:reward-punish");
        grant(ok, "honor:sanction-activity");
        grant(disabled, "honor:reward-punish");
        jdbc.update("UPDATE admin_user SET status = 1 WHERE id = ?", disabled);
        grant(plain, "user:list");

        List<String> named = audit.punishersWithoutAnySanctionScope();
        assertTrue(named.contains("audit-typo-" + tag + "（组织部门）"), named.toString());
        assertFalse(named.stream().anyMatch(s -> s.startsWith("audit-ok-" + tag)), "有档位权限的不点名");
        assertFalse(named.stream().anyMatch(s -> s.startsWith("audit-off-" + tag)), "停用的账号不点名");
        assertFalse(named.stream().anyMatch(s -> s.startsWith("audit-plain-" + tag)), "没开单权的不相干");

        audit.warnOnStartup(); // 只打日志，不抛
    }

    private long admin(String username, String department) {
        jdbc.update("INSERT INTO admin_user (username, password, real_name, department, is_super_admin, status, "
                + "create_time, is_deleted) VALUES (?, 'x', ?, ?, 0, 0, NOW(), 0)", username, username, department);
        return jdbc.queryForObject("SELECT id FROM admin_user WHERE username = ?", Long.class, username);
    }

    private void grant(long adminId, String code) {
        jdbc.update("INSERT INTO admin_permission (admin_user_id, permission_id, create_time, is_deleted) "
                + "SELECT ?, id, NOW(), 0 FROM permission WHERE code = ?", adminId, code);
    }
}
