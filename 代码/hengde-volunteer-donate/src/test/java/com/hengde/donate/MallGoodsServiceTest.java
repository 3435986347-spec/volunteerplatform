package com.hengde.donate;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.dto.MallGoodsSaveDTO;
import com.hengde.donate.dto.MallGoodsSpecDTO;
import com.hengde.donate.service.MallGoodsService;
import com.hengde.donate.vo.MallGoodsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商品的增删改查与审核状态机。
 *
 * <p>并发与隔离交错见 {@link MallGoodsReviewIsolationTest}——那里管「快照里还是旧状态」那一类；
 * 本类管顺序调用下的控制流：谁能提交、谁能通过、改了以后回到哪个状态。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MallGoodsServiceTest {

    private static final long ADMIN_ID = 993_900L;

    @Autowired
    private MallGoodsService goodsService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM mall_goods_spec WHERE goods_id IN "
                + "(SELECT id FROM mall_goods WHERE name LIKE '用例商品%')");
        jdbcTemplate.update("DELETE FROM mall_goods WHERE name LIKE '用例商品%'");
    }

    // ---------------- 新增与删除 ----------------

    @Test
    void createLandsAsDraftWithSpecs() {
        Long id = goodsService.create(dto("用例商品A", spec(null, "大号", 50, 3)), ADMIN_ID);

        MallGoodsVO vo = goodsService.detailForAdmin(id);
        assertEquals(MallGoodsStatus.DRAFT, vo.getStatus(), "新增落草稿，不直接进审核队列");
        assertEquals("草稿", vo.getStatusLabel());
        assertEquals(1, vo.getSpecs().size());
        assertEquals(50, vo.getMinPoints());
        assertEquals(3, vo.getTotalStock());
    }

    /** 库存只存在规格上，没有规格就没有库存——新增时必须至少给一条。 */
    @Test
    void createWithoutSpecIsRejected() {
        MallGoodsSaveDTO dto = new MallGoodsSaveDTO();
        dto.setName("用例商品B");
        assertThrows(BusinessException.class, () -> goodsService.create(dto, ADMIN_ID));
    }

    @Test
    void createRequiresOperator() {
        assertThrows(BusinessException.class,
                () -> goodsService.create(dto("用例商品C", spec(null, "均码", 10, 1)), null));
    }

    /**
     * 删除商品时<b>规格必须一起软删</b>。
     *
     * <p>漏掉的话库里会留下「商品没了、规格还在」的状态——虽然下单侧的 CAS 还有
     * {@code g.is_deleted = 0} 兜着，但下一个写查询的人就会踩到。</p>
     */
    @Test
    void deleteAlsoSoftDeletesSpecs() {
        Long id = goodsService.create(dto("用例商品D", spec(null, "均码", 20, 5)), ADMIN_ID);

        goodsService.delete(id);

        assertThrows(BusinessException.class, () -> goodsService.detailForAdmin(id));
        assertEquals(0, jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM mall_goods_spec WHERE goods_id = ? AND is_deleted = 0",
                        Integer.class, id),
                "商品软删后不得留下未删的规格行");
    }

    @Test
    void deleteRejectsUnknownId() {
        assertThrows(BusinessException.class, () -> goodsService.delete(8_888_888L));
    }

    // ---------------- 规格全量替换 ----------------

    /**
     * 传了 id 的原地更新、没传的新增、库里有而这次没传的软删。
     *
     * <p><b>不能实现成「先全删再全插」</b>：那样每改一次商品名，规格就会换一批新 id，
     * 而 {@code mall_order.spec_id} 指着它们——还库存会找不到人。
     * 本用例的第一条断言（id 不变）就是钉这个的。</p>
     */
    @Test
    void specsAreReplacedInPlaceKeepingIds() {
        Long id = goodsService.create(dto("用例商品E",
                spec(null, "大号", 50, 3), spec(null, "小号", 30, 2)), ADMIN_ID);
        Long keptSpecId = goodsService.detailForAdmin(id).getSpecs().get(0).getId();

        MallGoodsSaveDTO dto = dto("用例商品E", spec(keptSpecId, "大号", 55, 9));
        goodsService.update(id, dto);

        MallGoodsVO vo = goodsService.detailForAdmin(id);
        assertEquals(1, vo.getSpecs().size(), "没传的那条应被软删");
        assertEquals(keptSpecId, vo.getSpecs().get(0).getId(), "传了 id 的规格必须原地更新、id 不变");
        assertEquals(55, vo.getSpecs().get(0).getPoints());
        assertEquals(9, vo.getSpecs().get(0).getStock());
    }

    @Test
    void duplicateSpecNamesAreRejected() {
        Long id = goodsService.create(dto("用例商品F", spec(null, "均码", 10, 1)), ADMIN_ID);
        assertThrows(BusinessException.class,
                () -> goodsService.update(id, dto("用例商品F", spec(null, "同名", 1, 1), spec(null, "同名", 2, 2))));
    }

    /** 规格 id 必须属于本商品，否则就是拿别人的规格挂到自己名下。 */
    @Test
    void foreignSpecIdIsRejected() {
        Long a = goodsService.create(dto("用例商品G", spec(null, "均码", 10, 1)), ADMIN_ID);
        Long b = goodsService.create(dto("用例商品H", spec(null, "均码", 10, 1)), ADMIN_ID);
        Long specOfA = goodsService.detailForAdmin(a).getSpecs().get(0).getId();

        assertThrows(BusinessException.class,
                () -> goodsService.update(b, dto("用例商品H", spec(specOfA, "均码", 10, 1))));
    }

    @Test
    void updateRejectsEmptySpecList() {
        Long id = goodsService.create(dto("用例商品I", spec(null, "均码", 10, 1)), ADMIN_ID);
        MallGoodsSaveDTO dto = new MallGoodsSaveDTO();
        dto.setName("用例商品I");
        dto.setSpecs(List.of());
        assertThrows(BusinessException.class, () -> goodsService.update(id, dto));
    }

    // ---------------- 审核状态机 ----------------

    @Test
    void submitThenApprovePutsItOnSale() {
        Long id = goodsService.create(dto("用例商品J", spec(null, "均码", 10, 1)), ADMIN_ID);

        goodsService.submit(id);
        assertEquals(MallGoodsStatus.PENDING, goodsService.detailForAdmin(id).getStatus());

        goodsService.approve(id, ADMIN_ID);
        MallGoodsVO vo = goodsService.detailForAdmin(id);
        assertEquals(MallGoodsStatus.ON_SALE, vo.getStatus());
        assertEquals(ADMIN_ID, vo.getReviewBy());
    }

    /** 驳回记原因；驳回稿可以改完再提交（否则填了原因也没处落实）。 */
    @Test
    void rejectRecordsReasonAndAllowsResubmit() {
        Long id = goodsService.create(dto("用例商品K", spec(null, "均码", 10, 1)), ADMIN_ID);
        goodsService.submit(id);

        goodsService.reject(id, "图片不清晰", ADMIN_ID);
        MallGoodsVO vo = goodsService.detailForAdmin(id);
        assertEquals(MallGoodsStatus.REJECTED, vo.getStatus());
        assertEquals("图片不清晰", vo.getRejectReason());

        goodsService.submit(id);
        assertEquals(MallGoodsStatus.PENDING, goodsService.detailForAdmin(id).getStatus());
    }

    /** 审核是 CAS：不在待审核态的单，approve / reject 都必须失败。 */
    @Test
    void approveAndRejectOnlyWorkOnPending() {
        Long id = goodsService.create(dto("用例商品L", spec(null, "均码", 10, 1)), ADMIN_ID);

        assertThrows(BusinessException.class, () -> goodsService.approve(id, ADMIN_ID), "草稿不可直接过审");
        assertThrows(BusinessException.class, () -> goodsService.reject(id, "理由", ADMIN_ID));

        goodsService.submit(id);
        goodsService.approve(id, ADMIN_ID);
        assertThrows(BusinessException.class, () -> goodsService.approve(id, ADMIN_ID), "不得重复过审");
    }

    @Test
    void auditRequiresOperatorAndReason() {
        Long id = goodsService.create(dto("用例商品M", spec(null, "均码", 10, 1)), ADMIN_ID);
        goodsService.submit(id);

        assertThrows(BusinessException.class, () -> goodsService.approve(id, null), "操作人不能为空");
        assertThrows(BusinessException.class, () -> goodsService.reject(id, "  ", ADMIN_ID), "驳回必须写原因");
    }

    @Test
    void submitOnlyWorksOnDraftOrRejected() {
        Long id = goodsService.create(dto("用例商品N", spec(null, "均码", 10, 1)), ADMIN_ID);
        goodsService.submit(id);
        assertThrows(BusinessException.class, () -> goodsService.submit(id), "已在待审核的不得重复提交");
    }

    // ---------------- 可见性 ----------------

    /**
     * 志愿者端只看得到「已上架且未隐藏」。
     *
     * <p>草稿 / 待审核 / 驳回 / 已停用 / 已隐藏一律不可见——与活动「仅已发布可见」同口径，
     * 草稿泄露是同一类问题。</p>
     */
    @Test
    void volunteerSeesOnlyOnSaleAndNotHidden() {
        Long draft = goodsService.create(dto("用例商品O草稿", spec(null, "均码", 10, 1)), ADMIN_ID);
        Long onSale = goodsService.create(dto("用例商品P上架", spec(null, "均码", 10, 1)), ADMIN_ID);
        Long hidden = goodsService.create(dto("用例商品Q隐藏", spec(null, "均码", 10, 1)), ADMIN_ID);
        for (Long id : List.of(onSale, hidden)) {
            goodsService.submit(id);
            goodsService.approve(id, ADMIN_ID);
        }
        goodsService.updateDisplay(hidden, null, 1);

        List<String> visible = goodsService.listForVolunteer(new PageQuery(), "用例商品")
                .getRecords().stream().map(MallGoodsVO::getName).toList();
        assertTrue(visible.contains("用例商品P上架"));
        assertFalse(visible.contains("用例商品O草稿"), "草稿不得出现在志愿者端");
        assertFalse(visible.contains("用例商品Q隐藏"), "隐藏的不得出现在志愿者端");

        assertThrows(BusinessException.class, () -> goodsService.detailForVolunteer(draft));
        assertThrows(BusinessException.class, () -> goodsService.detailForVolunteer(hidden));
    }

    /**
     * 志愿者端不得带出审核痕迹——审核人与驳回原因是内部信息，不是给买家看的。
     *
     * <p>断言两侧对照着做：同一个商品，管理端<b>看得到</b> {@code reviewBy}、志愿者端<b>看不到</b>。
     * 只断言一侧的话，把 {@code toVO} 的 {@code forAdmin} 分支整个删掉也能全绿。</p>
     */
    @Test
    void volunteerViewHidesAuditTrail() {
        Long id = goodsService.create(dto("用例商品R", spec(null, "均码", 10, 1)), ADMIN_ID);
        goodsService.submit(id);
        goodsService.approve(id, ADMIN_ID);

        assertEquals(ADMIN_ID, goodsService.detailForAdmin(id).getReviewBy(), "管理端看得到审核人");
        assertNull(goodsService.detailForVolunteer(id).getReviewBy(), "志愿者端不得带出审核人");
        assertNull(goodsService.detailForVolunteer(id).getRejectReason(), "志愿者端不得带出驳回原因");
    }

    /**
     * 重新过审时<b>上一次的驳回原因要被清掉</b>。
     *
     * <p>不清的话，一个正在售卖的商品会一直挂着「图片不清晰」，误导下一位审核人——
     * 与 {@code update()} 退回重审时清痕迹是同一条理由（那条由
     * {@link MallGoodsReviewIsolationTest} 钉着，这条管审核这一侧）。</p>
     */
    @Test
    void reApprovalClearsTheStaleRejectReason() {
        Long id = goodsService.create(dto("用例商品S", spec(null, "均码", 10, 1)), ADMIN_ID);
        goodsService.submit(id);
        goodsService.reject(id, "图片不清晰", ADMIN_ID);
        assertEquals("图片不清晰", goodsService.detailForAdmin(id).getRejectReason());

        goodsService.submit(id);
        goodsService.approve(id, ADMIN_ID);

        assertNull(goodsService.detailForAdmin(id).getRejectReason(),
                "已上架的商品不该还挂着上一次的驳回原因");
    }

    // ---------------- helpers ----------------

    private MallGoodsSaveDTO dto(String name, MallGoodsSpecDTO... specs) {
        MallGoodsSaveDTO dto = new MallGoodsSaveDTO();
        dto.setName(name);
        dto.setSpecs(List.of(specs));
        return dto;
    }

    private MallGoodsSpecDTO spec(Long id, String name, int points, int stock) {
        MallGoodsSpecDTO dto = new MallGoodsSpecDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setPoints(points);
        dto.setStock(stock);
        return dto;
    }
}
