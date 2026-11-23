package com.hengde.honor;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.honor.constant.MedalConditionType;
import com.hengde.honor.constant.MedalStatus;
import com.hengde.honor.dto.MedalSaveDTO;
import com.hengde.honor.entity.HonorMedal;
import com.hengde.honor.service.MedalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 勋章定义与<b>样式审核</b>（第一重审核）验证。<b>需本机 Docker</b>（MySQL + Redis）。
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MedalServiceTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final Long AUDITOR = 9001L;

    private MedalService medalService;

    @Autowired
    public void setMedalService(MedalService medalService) {
        this.medalService = medalService;
    }

    // ================= 状态机 =================

    @Test
    void create_landsAsDraft() {
        Long id = medalService.create(dto("新建即草稿"));
        assertEquals(MedalStatus.DRAFT, medalService.requireMedal(id).getStatus(),
                "新建落草稿——没提交就能发放的话，样式审核这一重就不存在了");
    }

    @Test
    void fullApprovalFlow_draftToEnabled() {
        Long id = medalService.create(dto("正常通过"));
        medalService.submit(id);
        assertEquals(MedalStatus.PENDING, medalService.requireMedal(id).getStatus());

        medalService.approve(id, AUDITOR);

        HonorMedal medal = medalService.requireMedal(id);
        assertEquals(MedalStatus.ENABLED, medal.getStatus());
        assertEquals(AUDITOR, medal.getReviewBy(), "应留下审核人");
        assertNotNull(medal.getReviewTime(), "应留下审核时间");
    }

    @Test
    void reject_recordsReasonAndAllowsResubmit() {
        Long id = medalService.create(dto("驳回重交"));
        medalService.submit(id);
        medalService.reject(id, "图标分辨率过低", AUDITOR);

        HonorMedal rejected = medalService.requireMedal(id);
        assertEquals(MedalStatus.REJECTED, rejected.getStatus());
        assertEquals("图标分辨率过低", rejected.getRejectReason());

        medalService.submit(id);
        assertEquals(MedalStatus.PENDING, medalService.requireMedal(id).getStatus(),
                "驳回后改完应能重新提交");
    }

    @Test
    void approve_isCasProtected_secondCallFails() {
        Long id = medalService.create(dto("并发审核"));
        medalService.submit(id);
        medalService.approve(id, AUDITOR);

        assertThrows(BusinessException.class, () -> medalService.approve(id, AUDITOR),
                "重复审核应被 CAS 拦住，而不是覆盖一次审核痕迹");
    }

    @Test
    void submit_rejectedWhenNotDraftOrRejected() {
        Long id = medalService.create(dto("状态守卫"));
        medalService.submit(id);
        medalService.approve(id, AUDITOR);

        assertThrows(BusinessException.class, () -> medalService.submit(id),
                "已启用的勋章不该能再次提交审核");
    }

    @Test
    void auditorMustNotBeNull() {
        Long id = medalService.create(dto("审核人非空"));
        medalService.submit(id);
        assertThrows(BusinessException.class, () -> medalService.approve(id, null));
        assertThrows(BusinessException.class, () -> medalService.reject(id, "x", null));
    }

    // ================= 改动已过审样式必须重审 =================

    /**
     * <b>改了已启用的勋章要退回待审核</b>。
     *
     * <p>否则存在一条绕过审核的路：先提交一版素净的图标过审，通过后再把图标/名称改成别的，
     * 而它仍然挂着「已启用」，可以直接发放——样式审核就形同虚设了。</p>
     */
    @Test
    void updateEnabledMedal_fallsBackToPendingReview() {
        Long id = medalService.create(dto("过审后偷改"));
        medalService.submit(id);
        medalService.approve(id, AUDITOR);

        MedalSaveDTO changed = dto("过审后偷改");
        changed.setIconUrl("https://example.com/another-icon.png");
        medalService.update(id, changed);

        HonorMedal medal = medalService.requireMedal(id);
        assertEquals(MedalStatus.PENDING, medal.getStatus(), "改动已过审样式必须重新审核");
        assertNull(medal.getReviewBy(), "重审前应清掉上一次的审核痕迹");
        assertNull(medal.getReviewTime());
    }

    /**
     * <b>审核中的勋章不许改</b>。
     *
     * <p>否则审核人打开待审内容之后，管理员还能把它换掉，审核人点「通过」时批准的
     * 就不是他看过的那一版。要改先等审核结果，或者先驳回。</p>
     */
    @Test
    void updatePendingMedal_isRejected() {
        Long id = medalService.create(dto("审核中偷改"));
        medalService.submit(id);

        MedalSaveDTO changed = dto("审核中偷改");
        changed.setIconUrl("https://example.com/swapped-icon.png");
        BusinessException e = assertThrows(BusinessException.class, () -> medalService.update(id, changed),
                "待审核期间不该允许修改内容");
        assertTrue(e.getMessage().contains("审核"), "提示应说明原因：" + e.getMessage());

        assertEquals("https://example.com/medal.png", medalService.requireMedal(id).getIconUrl(),
                "内容不该被改动");
    }

    @Test
    void updateSort_doesNotResetReviewState() {
        Long id = medalService.create(dto("排序不重审"));
        medalService.submit(id);
        medalService.approve(id, AUDITOR);

        medalService.updateSort(id, 42);

        HonorMedal medal = medalService.requireMedal(id);
        assertEquals(MedalStatus.ENABLED, medal.getStatus(), "排序是纯展示属性，不该触发重审");
        assertEquals(42, medal.getSort());
    }

    // ================= 停用 / 启用 / 删除 =================

    @Test
    void disableAndEnable_roundTrip() {
        Long id = medalService.create(dto("停用再启用"));
        medalService.submit(id);
        medalService.approve(id, AUDITOR);

        medalService.disable(id);
        assertEquals(MedalStatus.DISABLED, medalService.requireMedal(id).getStatus());
        assertThrows(BusinessException.class, () -> medalService.disable(id), "重复停用应被拦");

        medalService.enable(id);
        assertEquals(MedalStatus.ENABLED, medalService.requireMedal(id).getStatus(),
                "此前已过审的样式重新启用无需再审");
    }

    @Test
    void disable_rejectedWhenNotEnabled() {
        Long id = medalService.create(dto("草稿不能停用"));
        assertThrows(BusinessException.class, () -> medalService.disable(id));
    }

    @Test
    void delete_removesDraft() {
        Long id = medalService.create(dto("删草稿"));
        medalService.delete(id);
        assertThrows(BusinessException.class, () -> medalService.requireMedal(id));
    }

    // ================= 入参校验 =================

    @Test
    void thresholdConditions_requirePositiveThreshold() {
        MedalSaveDTO dto = dto("缺阈值");
        dto.setConditionType(MedalConditionType.SERVICE_MINUTES);
        dto.setConditionThreshold(null);
        assertThrows(BusinessException.class, () -> medalService.create(dto),
                "有阈值的条件必须填阈值，否则进度条算不出来只能显示空的");
    }

    @Test
    void manualCondition_clearsThreshold() {
        MedalSaveDTO dto = dto("手动带阈值");
        dto.setConditionType(MedalConditionType.MANUAL);
        dto.setConditionThreshold(999L);

        HonorMedal medal = medalService.requireMedal(medalService.create(dto));

        assertNull(medal.getConditionThreshold(),
                "手动授予不该留着阈值——那会让人以为它会自动发放");
    }

    /**
     * 从「有阈值的条件」改回「手动授予」时，旧阈值必须被<b>清空</b>。
     *
     * <p>MyBatis-Plus 的 {@code updateById} 默认跳过 null 字段，直觉写法会让旧阈值静默留在库里，
     * 之后这枚勋章既显示「手动授予」又挂着一个阈值，进度条按残留值计算。</p>
     */
    @Test
    void switchingBackToManual_clearsStaleThreshold() {
        MedalSaveDTO withThreshold = dto("阈值改手动");
        withThreshold.setConditionType(MedalConditionType.SERVICE_MINUTES);
        withThreshold.setConditionThreshold(300L);
        Long id = medalService.create(withThreshold);

        MedalSaveDTO manual = dto("阈值改手动");
        manual.setConditionType(MedalConditionType.MANUAL);
        medalService.update(id, manual);

        HonorMedal medal = medalService.requireMedal(id);
        assertEquals(MedalConditionType.MANUAL, medal.getConditionType());
        assertNull(medal.getConditionThreshold(), "改为手动授予后旧阈值必须清掉");
    }

    @Test
    void unknownConditionType_isRejected() {
        MedalSaveDTO dto = dto("非法条件");
        dto.setConditionType(99);
        assertThrows(BusinessException.class, () -> medalService.create(dto));
    }

    @Test
    void listForAdmin_filtersByStatus() {
        Long draft = medalService.create(dto("筛选草稿"));
        Long enabled = medalService.create(dto("筛选已启用"));
        medalService.submit(enabled);
        medalService.approve(enabled, AUDITOR);

        assertTrue(medalService.listForAdmin(MedalStatus.DRAFT).stream()
                .anyMatch(v -> draft.equals(v.getId())));
        assertTrue(medalService.listForAdmin(MedalStatus.DRAFT).stream()
                .noneMatch(v -> enabled.equals(v.getId())));
        assertTrue(medalService.listEnabled().stream()
                .anyMatch(m -> enabled.equals(m.getId())));
        assertTrue(medalService.listEnabled().stream()
                .noneMatch(m -> draft.equals(m.getId())), "未过审的勋章不该出现在可发放列表里");
    }

    // ---------- helpers ----------

    private MedalSaveDTO dto(String name) {
        MedalSaveDTO dto = new MedalSaveDTO();
        dto.setName(name + "_" + SEQ.incrementAndGet());
        dto.setIconUrl("https://example.com/medal.png");
        dto.setDescription("测试勋章");
        dto.setConditionType(MedalConditionType.MANUAL);
        dto.setRewardPoints(0);
        dto.setSort(0);
        return dto;
    }
}
