package com.hengde.organization.service;

import com.hengde.organization.dao.AdminPermissionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时检查：有开单权、却一个处置档位都开不了的后台账号。
 *
 * <p>V85（2026-09-29）起「限制参加活动 / 限制发布社区 / 拒绝使用本程序」各要一个权限点，
 * 存量账号由 V86 / V92 按 {@code admin_user.department} 文字补授（组织部 / 宣传部 / 秘书部 / 理事会）。
 * 部门文字对不上的账号会失去原本能开的档位，而开单弹窗只是把那几档藏起来——<b>没有任何地方会说出来</b>。
 * 这里在启动时把这些账号打成 WARN，超管据此到「子账号与权限」里手工补授。只读、不改任何授权。</p>
 *
 * @author hengde
 */
@Slf4j
@Component
public class SanctionPermissionAudit {

    private AdminPermissionMapper adminPermissionMapper;

    @Autowired
    public void setAdminPermissionMapper(AdminPermissionMapper adminPermissionMapper) {
        this.adminPermissionMapper = adminPermissionMapper;
    }

    /** 有开单权却没有任何处置档位权限的启用中账号（「账号（部门）」）。 */
    public List<String> punishersWithoutAnySanctionScope() {
        return adminPermissionMapper.selectPunishersWithoutAnySanctionScope();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnOnStartup() {
        try {
            List<String> accounts = punishersWithoutAnySanctionScope();
            if (!accounts.isEmpty()) {
                log.warn("以下后台账号能开奖惩单、却开不出任何一档处置（限制参加活动 honor:sanction-activity / "
                        + "限制发布社区 honor:sanction-community / 拒绝使用本程序 honor:sanction-all 一个都没有）；"
                        + "多半是部门文字与「组织部 / 宣传部 / 秘书部 / 理事会」对不上，请在「子账号与权限」里按部门补授：{}",
                        accounts);
            }
        } catch (RuntimeException e) {
            log.warn("处置权限自检失败（不影响启动）：{}", e.getMessage());
        }
    }
}
