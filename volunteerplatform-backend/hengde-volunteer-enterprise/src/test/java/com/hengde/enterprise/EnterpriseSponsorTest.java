package com.hengde.enterprise;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.dto.MallGoodsSaveDTO;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.service.MallGoodsService;
import com.hengde.donate.service.MallOrderService;
import com.hengde.donate.service.MallVerifierService;
import com.hengde.donate.vo.MallGoodsVO;
import com.hengde.donate.vo.SponsorOrderVO;
import com.hengde.enterprise.dto.EnterprisePointDTOs;
import com.hengde.enterprise.service.EnterpriseAdminService;
import com.hengde.enterprise.service.EnterprisePointService;
import com.hengde.enterprise.service.EnterpriseSponsorService;
import com.hengde.enterprise.vo.EnterprisePointVOs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.hengde.enterprise.EnterpriseTestSupport.ADMIN;
import static com.hengde.enterprise.EnterpriseTestSupport.assertMessage;
import static com.hengde.enterprise.SponsorTestSupport.goods;
import static com.hengde.enterprise.SponsorTestSupport.normalEnterprise;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 爱心企业批·商品段：赞助商品只能管自己的、走原有后台审核、企业主页展示 / 企业暂停即商品不可见不可兑换、恢复复原 /
 * 企业核销员只能核销本企业赞助的 / 企业积分账本（领取后补记、幂等、快递费与 0 分不记、后台调整的幂等键与余额）/ 企业看兑换单不露取货码。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class EnterpriseSponsorTest {

    @Autowired
    private EnterpriseAdminService adminService;
    @Autowired
    private EnterpriseSponsorService sponsorService;
    @Autowired
    private EnterprisePointService pointService;
    @Autowired
    private MallGoodsService goodsService;
    @Autowired
    private MallOrderService orderService;
    @Autowired
    private MallVerifierService verifierService;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sponsorGoods_ownOnly_sameReview_homepage() {
        Long mine = normalEnterprise(adminService, "企业甲");
        Long other = normalEnterprise(adminService, "企业乙");
        MallGoodsSaveDTO dto = goods("赞助水杯", 20, 5);
        dto.setSponsorName("冒充的名字");
        dto.setRequireCouponId(123456L);
        Long goodsId = sponsorService.createGoods(mine, dto);
        MallGoodsVO draft = goodsService.detailForSponsor(goodsId, mine);
        assertEquals(0, draft.getStatus());
        assertTrue(draft.getSponsorName().startsWith("企业甲"), "赞助方名称取企业名称快照：" + draft.getSponsorName());
        assertNull(draft.getRequireCouponId(), "企业不能设必须持卷");

        assertMessage("商品不存在", () -> goodsService.detailForSponsor(goodsId, other));
        assertMessage("商品不存在或正在审核中", () -> goodsService.updateForSponsor(goodsId, other, goods("抢改", 1, 1)));
        assertMessage("商品不存在或当前状态不可提交审核", () -> goodsService.submitForSponsor(goodsId, other));
        assertMessage("商品不存在", () -> goodsService.deleteForSponsor(goodsId, other));

        goodsService.submitForSponsor(goodsId, mine);
        goodsService.approve(goodsId, ADMIN);
        assertTrue(ids(sponsorService.goodsForVolunteer(mine, new PageQuery()).getRecords()).contains(goodsId), "企业主页有它");
        assertFalse(ids(sponsorService.goodsForVolunteer(other, new PageQuery()).getRecords()).contains(goodsId));

        MallGoodsSaveDTO edit = goods("改名水杯", 30, 5);
        edit.setRequireCouponId(999L);
        goodsService.updateForSponsor(goodsId, mine, edit);
        MallGoodsVO pending = goodsService.detailForSponsor(goodsId, mine);
        assertEquals(1, pending.getStatus(), "改已上架的退回待审核");
        assertNull(pending.getRequireCouponId());
        assertFalse(ids(sponsorService.goodsForVolunteer(mine, new PageQuery()).getRecords()).contains(goodsId));
        assertMessage("商品不存在或正在审核中", () -> goodsService.updateForSponsor(goodsId, mine, goods("审核中再改", 1, 1)));

        goodsService.approve(goodsId, ADMIN);
        goodsService.hideForSponsor(goodsId, mine, 1);
        assertFalse(ids(goodsService.listForVolunteer(new PageQuery(), null).getRecords()).contains(goodsId), "企业自己隐藏");
        goodsService.hideForSponsor(goodsId, mine, 0);
        goodsService.deleteForSponsor(goodsId, mine);
        assertMessage("商品不存在", () -> goodsService.detailForSponsor(goodsId, mine));
    }

    @Test
    void pausingTheEnterprise_hidesItsGoods_andBlocksOrdering_resumeRestores() {
        Long ent = normalEnterprise(adminService, "暂停企业");
        Long goodsId = approvedGoods(ent, 10, 20);
        Long spec = SponsorTestSupport.specOf(jdbc, goodsId);
        Long buyer = SponsorTestSupport.volunteer(volunteerMapper, cryptoUtil, EnterpriseTestSupport.phone());
        SponsorTestSupport.givePoints(jdbc, buyer, 100);
        orderService.placeOrder(buyer, spec);

        adminService.pause(ent, "缺货", ADMIN);
        assertFalse(ids(goodsService.listForVolunteer(new PageQuery(), null).getRecords()).contains(goodsId), "暂停的企业商品不可见");
        assertMessage("商品不存在", () -> goodsService.detailForVolunteer(goodsId));
        assertMessage("企业不存在或当前不可用", () -> sponsorService.goodsForVolunteer(ent, new PageQuery()));
        Long buyer2 = SponsorTestSupport.volunteer(volunteerMapper, cryptoUtil, EnterpriseTestSupport.phone());
        SponsorTestSupport.givePoints(jdbc, buyer2, 100);
        // 直接拿规格 id 下单也不行（扣库存那条语句带着条件），且说清楚原因——不能落到「库存不足」
        assertMessage("赞助企业暂停合作", () -> orderService.placeOrder(buyer2, spec));
        assertMessage("企业不存在或当前不可用", () -> sponsorService.createGoods(ent, goods("暂停后发", 1, 1)));

        adminService.resume(ent, ADMIN);
        assertTrue(ids(goodsService.listForVolunteer(new PageQuery(), null).getRecords()).contains(goodsId), "恢复即复原");
        assertTrue(orderService.placeOrder(buyer2, spec).getId() > 0);

        adminService.delete(ent, ADMIN);
        assertFalse(ids(goodsService.listForVolunteer(new PageQuery(), null).getRecords()).contains(goodsId), "删除的企业商品不可见");
    }

    @Test
    void enterpriseVerifier_onlyVerifiesItsOwnSponsoredGoods() {
        Long ent = normalEnterprise(adminService, "核销企业");
        Long other = normalEnterprise(adminService, "别家企业");
        Long ownGoods = approvedGoods(ent, 10, 20);
        Long otherGoods = approvedGoods(other, 10, 20);
        String verifierPhone = EnterpriseTestSupport.phone();
        Long verifier = SponsorTestSupport.volunteer(volunteerMapper, cryptoUtil, verifierPhone);
        assertMessage("没有找到这个手机号的已实名志愿者", () -> verifierService.assignForEnterprise(ent, EnterpriseTestSupport.phone(), null));
        Long vid = verifierService.assignForEnterprise(ent, verifierPhone, "门店");
        assertMessage("该志愿者已是核销员", () -> verifierService.assignForEnterprise(other, verifierPhone, null));
        assertEquals(1, verifierService.listForEnterprise(ent, new PageQuery()).getRecords().size());
        assertTrue(verifierService.listForEnterprise(ent, new PageQuery()).getRecords().get(0).getVolunteerName().contains("*"), "姓名只留姓");

        String ownCode = readyCode(ownGoods);
        String otherCode = readyCode(otherGoods);
        assertMessage("取货码无效", () -> orderService.verifyByVerifier(otherCode, verifier));
        assertEquals(3, orderService.verifyByVerifier(ownCode, verifier).getStatus());

        assertMessage("核销员记录不存在", () -> verifierService.removeForEnterprise(vid, other));
        verifierService.removeForEnterprise(vid, ent);
        assertMessage("您不是核销员", () -> orderService.verifyByVerifier(readyCode(ownGoods), verifier));
    }

    @Test
    void ledger_creditsPickedOrdersOnce_excludesShippingAndZero_adjustIdempotentAndNeverNegative() {
        Long ent = normalEnterprise(adminService, "账本企业");
        Long goodsId = approvedGoods(ent, 10, 20);
        LocalDateTime since = LocalDateTime.now().minusMinutes(5);
        Long a = pickedOrder(goodsId);
        Long b = pickedOrder(goodsId);
        Long c = pickedOrder(goodsId);
        Long notYet = readyOrder(goodsId);
        jdbc.update("UPDATE mall_order SET points = 15, shipping_points = 5 WHERE id = ?", b);
        jdbc.update("UPDATE mall_order SET points = 0 WHERE id = ?", c);

        // 补记扫的是全库（同一上下文里别的用例刚核销的单也在窗口里），所以只断言本企业的流水
        pointService.creditPickedOrders(since);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM enterprise_point_record WHERE enterprise_id = ?", Integer.class, ent),
                "a 记 10、b 记 15-5=10、c 0 分不记、未领取的不记");
        pointService.creditPickedOrders(since);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM enterprise_point_record WHERE enterprise_id = ?", Integer.class, ent),
                "重跑不重复记");
        EnterprisePointVOs.Summary s = pointService.summary(ent);
        assertEquals(20L, s.getBalance());
        assertEquals(20L, s.getTotalExchanged());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM enterprise_point_record WHERE source_type = 1 AND source_id IN (?, ?)",
                Integer.class, c, notYet));
        assertTrue(pointService.records(ent, new PageQuery(), 1).getRecords().get(0).getRemark().startsWith("兑换入账："));

        EnterprisePointDTOs.Adjust deduct = adjust(-15, "兑换广告位一周", "req-" + EnterpriseTestSupport.next());
        assertEquals(5L, pointService.adjust(ent, deduct, ADMIN).getBalance());
        assertEquals(5L, pointService.adjust(ent, deduct, ADMIN).getBalance(), "同一个幂等键重放只记一次");
        EnterprisePointDTOs.Adjust reused = adjust(-1, "换了个说明", deduct.getRequestId());
        assertMessage("幂等键已用于另一笔调整", () -> pointService.adjust(ent, reused, ADMIN));
        assertMessage("企业积分余额不足（当前 5）", () -> pointService.adjust(ent, adjust(-6, "扣多了", "req-" + EnterpriseTestSupport.next()), ADMIN));
        assertMessage("调整数额不能为 0", () -> pointService.adjust(ent, adjust(0, "零", "req-" + EnterpriseTestSupport.next()), ADMIN));
        assertEquals(-15L, pointService.summary(ent).getTotalAdjusted());
    }

    @Test
    void sponsorOrders_noPickupCodeNoContacts_maskedName() {
        Long ent = normalEnterprise(adminService, "订单企业");
        Long goodsId = approvedGoods(ent, 10, 20);
        Long orderId = readyOrder(goodsId);
        List<SponsorOrderVO> rows = orderService.listForSponsor(ent, new PageQuery(), null).getRecords();
        assertEquals(1, rows.size());
        assertEquals(orderId, rows.get(0).getId());
        assertEquals("王**", rows.get(0).getVolunteerMaskedName());
        assertEquals(10, rows.get(0).getGoodsPoints());
        assertTrue(orderService.listForSponsor(normalEnterprise(adminService, "无关企业"), new PageQuery(), null).getRecords().isEmpty());
        Set<String> fields = new HashSet<>();
        for (Field f : SponsorOrderVO.class.getDeclaredFields()) {
            fields.add(f.getName());
        }
        assertEquals(Set.of("id", "orderNo", "goodsId", "goodsName", "specName", "goodsPoints", "deliveryType", "deliveryTypeLabel",
                "status", "statusLabel", "volunteerMaskedName", "createTime", "pickupTime"), fields,
                "企业看兑换单不许长出取货码、收件人电话地址、完整姓名");
    }

    // ================= 造数 =================

    private Long approvedGoods(Long ent, int points, int stock) {
        Long id = sponsorService.createGoods(ent, goods("赞助商品", points, stock));
        goodsService.submitForSponsor(id, ent);
        goodsService.approve(id, ADMIN);
        return id;
    }

    private Long readyOrder(Long goodsId) {
        Long buyer = SponsorTestSupport.volunteer(volunteerMapper, cryptoUtil, EnterpriseTestSupport.phone());
        SponsorTestSupport.givePoints(jdbc, buyer, 100);
        MallOrder o = orderService.placeOrder(buyer, SponsorTestSupport.specOf(jdbc, goodsId));
        orderService.approve(o.getId(), ADMIN);
        return o.getId();
    }

    private String readyCode(Long goodsId) {
        return jdbc.queryForObject("SELECT pickup_code FROM mall_order WHERE id = ?", String.class, readyOrder(goodsId));
    }

    private Long pickedOrder(Long goodsId) {
        Long id = readyOrder(goodsId);
        orderService.verify(jdbc.queryForObject("SELECT pickup_code FROM mall_order WHERE id = ?", String.class, id), ADMIN);
        return id;
    }

    static EnterprisePointDTOs.Adjust adjust(int amount, String remark, String requestId) {
        EnterprisePointDTOs.Adjust d = new EnterprisePointDTOs.Adjust();
        d.setAmount(amount);
        d.setRemark(remark);
        d.setRequestId(requestId);
        return d;
    }

    private static List<Long> ids(List<MallGoodsVO> rows) {
        return rows.stream().map(MallGoodsVO::getId).toList();
    }
}
