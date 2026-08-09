package com.hengde.honor.controller;

import com.hengde.auth.constant.SanctionScope;
import com.hengde.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

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
     * 「拒绝其使用本程序」只能由持有<b>全部限制能力</b>的账号开出
     * （xlsx Row 73「监察部拥有全部限制能力」）。
     *
     * <p>这一位判反的后果是所有部门都能下达最重的一档限制，Row 73 那句话也就失去了区分作用。</p>
     */
    @Test
    void assertScopeAllowed_onlyAllScopeNeedsTheExtraPermission() {
        // 最重的一档：没有权限必须被拒
        BusinessException ex = assertThrows(BusinessException.class,
                () -> AdminRewardPunishController.assertScopeAllowed(SanctionScope.ALL, false));
        assertTrue(ex.getMessage().contains("拒绝其使用本程序"), "实际：" + ex.getMessage());

        // 有权限则放行
        assertDoesNotThrow(
                () -> AdminRewardPunishController.assertScopeAllowed(SanctionScope.ALL, true));

        // 其余能力域不受本点影响——若把判定挂成方法级 @SaCheckPermission，
        // 「限制参加活动」这类日常处罚会被一并锁死，所以这条「不该收紧」的方向同样要钉住
        assertDoesNotThrow(
                () -> AdminRewardPunishController.assertScopeAllowed(SanctionScope.ACTIVITY, false));
        assertDoesNotThrow(
                () -> AdminRewardPunishController.assertScopeAllowed(SanctionScope.COMMUNITY, false));
        // 不附带处置的单（含全部奖励）更不该被拦
        assertDoesNotThrow(() -> AdminRewardPunishController.assertScopeAllowed(null, false));
    }
}
