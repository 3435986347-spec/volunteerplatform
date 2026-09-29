package com.hengde.honor.controller;

import com.hengde.auth.constant.SanctionScope;
import com.hengde.common.exception.BusinessException;
import com.hengde.honor.constant.PermissionCode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端奖惩控制器里两处<b>纯判定</b>的极性回归。纯单元用例，不起 Spring 上下文。
 *
 * <p><b>为什么值得单独一个类</b>：这两处判反了<b>没有任何征兆</b>——接口照常 200、照常返回数据，
 * 只是把不该给的东西给了出去；而 service 层的用例根本触碰不到 controller 传的是什么值。
 * 权限判定本身依赖 Sa-Token 静态上下文、在单测里压不住，于是把「权限 → 行为」这一步的
 * 纯函数部分抽出来，在这里把极性钉死。</p>
 *
 * <p><b>覆盖不到的仍要说清</b>：本类证明不了 controller 真的把
 * {@code StpAdminUtil.STP_LOGIC.hasPermission(...)} 的结果喂给了这两个方法。</p>
 *
 * @author hengde
 */
class AdminRewardPunishControllerTest {

    @Test
    void appealedOnly_polarityIsPinned() {
        assertFalse(AdminRewardPunishController.appealedOnly(true),
                "持有 honor:reward-punish（完整管理权）→ 不收窄，看得到全部");
        assertTrue(AdminRewardPunishController.appealedOnly(false),
                "只有 honor:reward-punish-appeal（受理权）→ 收窄，只看得到已进入申诉流程的单");
    }

    /**
     * 每一档限制由对应部门开出（协会 2026-09-16）：限制参加活动 → 组织部 / 限制发布社区 → 宣传部 /
     * 拒绝使用本程序 → 秘书部，理事会拥有全部。三档各一个点，谁都不蕴含谁——
     * 秘书部能开最重的一档，不等于它能开「限制参加活动」。
     *
     * <p>判错的后果没有任何征兆：接口照常返回单号，只是谁都能开。</p>
     */
    @Test
    void assertScopeAllowed_eachScopeNeedsItsOwnDepartmentPermission() {
        Map<Integer, String> required = Map.of(
                SanctionScope.ACTIVITY, PermissionCode.HONOR_SANCTION_ACTIVITY,
                SanctionScope.COMMUNITY, PermissionCode.HONOR_SANCTION_COMMUNITY,
                SanctionScope.COMMUNITY_POST, PermissionCode.HONOR_SANCTION_COMMUNITY,
                SanctionScope.COMMUNITY_COMMENT, PermissionCode.HONOR_SANCTION_COMMUNITY,
                SanctionScope.COMMUNITY_LIKE, PermissionCode.HONOR_SANCTION_COMMUNITY,
                SanctionScope.COMMUNITY_CHAT, PermissionCode.HONOR_SANCTION_COMMUNITY,
                SanctionScope.ALL, PermissionCode.HONOR_SANCTION_ALL);
        for (Map.Entry<Integer, String> e : required.entrySet()) {
            int scope = e.getKey();
            String code = e.getValue();
            // 没有任何限制权限：拒
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> AdminRewardPunishController.assertScopeAllowed(scope, c -> false), "档位 " + scope);
            assertTrue(ex.getMessage().contains(code), "报错要说清缺哪个点：" + ex.getMessage());
            // 只有这一档的点：放行
            assertDoesNotThrow(() -> AdminRewardPunishController.assertScopeAllowed(scope, code::equals), "档位 " + scope);
            // 只有别的档的点：拒——三个点互不蕴含
            for (String other : Set.copyOf(required.values())) {
                if (!other.equals(code)) {
                    assertThrows(BusinessException.class,
                            () -> AdminRewardPunishController.assertScopeAllowed(scope, other::equals),
                            "持 " + other + " 不能开档位 " + scope);
                }
            }
            // 理事会（三个点都有）：放行
            assertDoesNotThrow(() -> AdminRewardPunishController.assertScopeAllowed(scope, required::containsValue));
        }
        // 不附带处置的单（含全部奖励）不需要任何限制权限；未知取值交给 service 拒绝，这里不拦
        assertDoesNotThrow(() -> AdminRewardPunishController.assertScopeAllowed(null, c -> false));
        assertDoesNotThrow(() -> AdminRewardPunishController.assertScopeAllowed(99, c -> false));
    }
}
