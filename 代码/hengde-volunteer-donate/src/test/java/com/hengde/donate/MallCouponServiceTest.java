package com.hengde.donate;

import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.MallCouponGrantStatus;
import com.hengde.donate.constant.MallCouponType;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.dto.MallCouponGrantDTO;
import com.hengde.donate.dto.MallCouponSaveDTO;
import com.hengde.donate.service.MallCouponService;
import com.hengde.donate.vo.CouponGrantResultVO;
import com.hengde.donate.vo.MallCouponGrantVO;
import com.hengde.donate.vo.MallCouponVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卷定义、发放、作废、我的卷、可用卷（V3 卷批）。
 *
 * <p>用卷下单与退单由 {@link MallCouponOrderTest} 管，并发由 {@code MallCouponStressTest} 管。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MallCouponServiceTest {

    private static final long ADMIN = 7001L;
    private static final long GOODS_A = 993_001L;
    /** 标价 30 */
    private static final long SPEC_A1 = 993_101L;
    /** 标价 8——低于满减门槛 */
    private static final long SPEC_A2 = 993_102L;
    private static final long GOODS_B = 993_002L;
    private static final long SPEC_B1 = 993_201L;

    /** 手机号唯一化：共享容器里各用例都会建志愿者，撞号会让按手机号发卷变成「一号多账号」 */
    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime() % 10_000_000L);

    @Autowired
    private MallCouponService couponService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM mall_goods_spec WHERE goods_id IN (?, ?)", GOODS_A, GOODS_B);
        jdbc.update("DELETE FROM mall_goods WHERE id IN (?, ?)", GOODS_A, GOODS_B);
        insertGoods(GOODS_A, "保温杯", null);
        insertSpec(SPEC_A1, GOODS_A, "500ml", 30);
        insertSpec(SPEC_A2, GOODS_A, "迷你", 8);
        insertGoods(GOODS_B, "笔记本", null);
        insertSpec(SPEC_B1, GOODS_B, "A5", 50);
    }

    // ================= 卷定义 =================

    @Test
    void createValidatesTermsAndRefusesToSilentlyDropFields() {
        assertMessage("指定商品兑换卷必须指定商品",
                () -> couponService.create(dto(MallCouponType.EXCHANGE, null, null, null), ADMIN));
        // 兑换卷填了抵扣：静默丢掉的话，管理员以为发了一张「抵 20」的卷，实际发出去的是全额抵扣
        assertMessage("全额抵扣",
                () -> couponService.create(dto(MallCouponType.EXCHANGE, GOODS_A, null, 20), ADMIN));
        assertMessage("满减门槛不得低于抵扣积分",
                () -> couponService.create(dto(MallCouponType.DISCOUNT, null, 5, 20), ADMIN));
        assertMessage("大于 0 的抵扣积分",
                () -> couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 0), ADMIN));
        assertMessage("卷类型只能是",
                () -> couponService.create(dto(9, null, 20, 5), ADMIN));
        assertMessage("适用商品不存在",
                () -> couponService.create(dto(MallCouponType.EXCHANGE, 999_999_999L, null, null), ADMIN));

        MallCouponSaveDTO badWindow = dto(MallCouponType.DISCOUNT, null, 20, 5);
        badWindow.setValidEnd(badWindow.getValidStart());
        assertMessage("有效期止必须晚于有效期起", () -> couponService.create(badWindow, ADMIN));
    }

    @Test
    void editingDefinitionDoesNotRewriteIssuedCoupons() {
        Long couponId = couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 12), ADMIN);
        Long v1 = registered();
        couponService.grant(couponId, grant("rid-edit-1-" + System.nanoTime(), List.of(v1), null), ADMIN);

        MallCouponSaveDTO changed = dto(MallCouponType.DISCOUNT, null, 20, 5);
        couponService.update(couponId, changed);
        Long v2 = registered();
        couponService.grant(couponId, grant("rid-edit-2-" + System.nanoTime(), List.of(v2), null), ADMIN);

        assertEquals(12, discountOf(v1), "已发出的卷必须保持发放时的条款——事后改定义不得追溯");
        assertEquals(5, discountOf(v2), "改定义之后发的卷按新条款");
    }

    @Test
    void updateCanClearGoodsToMakeDiscountCouponStoreWide() {
        Long couponId = couponService.create(dto(MallCouponType.DISCOUNT, GOODS_A, 20, 5), ADMIN);
        couponService.update(couponId, dto(MallCouponType.DISCOUNT, null, 20, 5));
        // updateById 会跳过 null，「改成全场通用」会静默失效——勋章 update 被抓到过同一个坑
        assertNull(couponService.detailForAdmin(couponId).getGoodsId(), "适用商品必须能被清空为全场通用");
    }

    // ================= 发放 =================

    @Test
    void grantByIdsAndPhones_skipsIneligibleAndReportsUnmatched() {
        Long couponId = couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 5), ADMIN);
        Long ok = registered();
        String okPhone = nextPhone();
        Long okByPhone = insertVolunteer(okPhone, true, 0);
        Long guest = insertVolunteer(nextPhone(), false, 0);
        Long banned = insertVolunteer(nextPhone(), true, 1);
        Long cancelled = insertVolunteer(nextPhone(), true, 2);
        String unknownPhone = "13000000000";

        CouponGrantResultVO r = couponService.grant(couponId,
                grant("rid-mix-" + System.nanoTime(), List.of(ok, guest, banned, cancelled), List.of(okPhone, unknownPhone)),
                ADMIN);

        assertEquals(2, r.getGranted(), "只有已实名且账号正常的两人能拿到卷");
        assertEquals(List.of(unknownPhone), r.getUnmatchedPhones(), "查不到的手机号要逐条列出");
        assertTrue(r.getIneligibleVolunteerIds().containsAll(List.of(guest, banned, cancelled)),
                "游客 / 禁用 / 注销都不能发——实际=" + r.getIneligibleVolunteerIds());
        assertEquals(1, countGrants(couponId, ok));
        assertEquals(1, countGrants(couponId, okByPhone));
        assertEquals(0, countGrants(couponId, guest));
    }

    @Test
    void grantSnapshotsEveryTerm() {
        Long couponId = couponService.create(dto(MallCouponType.DISCOUNT, GOODS_A, 25, 7), ADMIN);
        Long v = registered();
        couponService.grant(couponId, grant("rid-snap-" + System.nanoTime(), List.of(v), null), ADMIN);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT g.*, c.valid_end AS def_end FROM mall_coupon_grant g JOIN mall_coupon c ON c.id = g.coupon_id "
                        + "WHERE g.coupon_id = ? AND g.volunteer_id = ?", couponId, v);
        assertEquals(MallCouponType.DISCOUNT, ((Number) row.get("type")).intValue());
        assertEquals(GOODS_A, ((Number) row.get("goods_id")).longValue());
        assertEquals(25, ((Number) row.get("threshold_points")).intValue());
        assertEquals(7, ((Number) row.get("discount_points")).intValue());
        assertEquals(row.get("def_end"), row.get("expire_time"), "到期时刻快照自卷定义的有效期止");
        assertEquals(MallCouponGrantStatus.UNUSED, ((Number) row.get("status")).intValue());
    }

    @Test
    void grantReplayWithSameRequestIdIsIdempotent() {
        Long couponId = couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 5), ADMIN);
        List<Long> people = List.of(registered(), registered(), registered());
        String rid = "rid-replay-" + System.nanoTime();

        CouponGrantResultVO first = couponService.grant(couponId, grant(rid, people, null), ADMIN);
        CouponGrantResultVO second = couponService.grant(couponId, grant(rid, people, null), ADMIN);

        assertEquals(3, first.getGranted());
        assertEquals(0, second.getGranted(), "重放不得多发");
        assertEquals(3, second.getAlreadyGranted(), "重放要如实报「已发过」，不能报 0 人");
        for (Long p : people) {
            assertEquals(1, countGrants(couponId, p));
        }
    }

    @Test
    void requestIdReusedForAnotherCouponIsAConflictNotASilentSkip() {
        Long a = couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 5), ADMIN);
        Long b = couponService.create(dto(MallCouponType.DISCOUNT, null, 30, 10), ADMIN);
        Long v = registered();
        String rid = "rid-reuse-" + System.nanoTime();
        couponService.grant(a, grant(rid, List.of(v), null), ADMIN);

        // 只判键存在会把「拿同一个键发另一张卷」伪装成「已发过」——那张 B 卷其实没发出去
        assertMessage("另一张卷", () -> couponService.grant(b, grant(rid, List.of(v), null), ADMIN));
        assertEquals(0, countGrants(b, v));
    }

    @Test
    void requestIdOutsideAsciiSafeSetIsRejected() {
        Long couponId = couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 5), ADMIN);
        Long v = registered();
        // 默认排序规则忽略重音：sýs 与 sys 在库里相等、在 Java 里不等（V34 那一课）
        assertMessage("幂等键只能由", () -> couponService.grant(couponId, grant("sýs-rid", List.of(v), null), ADMIN));
        assertMessage("幂等键只能由", () -> couponService.grant(couponId, grant("rid with space", List.of(v), null), ADMIN));
    }

    @Test
    void disabledOrExpiredCouponCannotBeGranted() {
        Long disabled = couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 5), ADMIN);
        couponService.updateStatus(disabled, MallCouponService.COUPON_DISABLED);
        Long v = registered();
        assertMessage("已停用", () -> couponService.grant(disabled, grant("rid-dis-" + System.nanoTime(), List.of(v), null), ADMIN));

        Long expired = couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 5), ADMIN);
        jdbc.update("UPDATE mall_coupon SET valid_start = ?, valid_end = ? WHERE id = ?",
                LocalDateTime.now().minusDays(10), LocalDateTime.now().minusDays(1), expired);
        assertMessage("已过有效期", () -> couponService.grant(expired, grant("rid-exp-" + System.nanoTime(), List.of(v), null), ADMIN));
    }

    @Test
    void grantTooManyTargetsIsRejected() {
        Long couponId = couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 5), ADMIN);
        List<Long> many = new java.util.ArrayList<>();
        for (long i = 0; i <= 1000; i++) {
            many.add(8_000_000L + i);
        }
        assertMessage("单次最多发放", () -> couponService.grant(couponId, grant("rid-many-" + System.nanoTime(), many, null), ADMIN));
    }

    @Test
    void disablingDoesNotAffectCouponsAlreadyIssued() {
        Long couponId = couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 5), ADMIN);
        Long v = registered();
        couponService.grant(couponId, grant("rid-dis2-" + System.nanoTime(), List.of(v), null), ADMIN);
        couponService.updateStatus(couponId, MallCouponService.COUPON_DISABLED);

        assertEquals(1, couponService.listUsableForSpec(v, SPEC_A1).size(), "停用只挡新发放，已发出的卷照常可用");
    }

    // ================= 作废 =================

    @Test
    void revokeOnlyUnusedAndSaysWhyOtherwise() {
        Long couponId = couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 5), ADMIN);
        Long v = registered();
        couponService.grant(couponId, grant("rid-rev-" + System.nanoTime(), List.of(v), null), ADMIN);
        Long grantId = grantIdOf(couponId, v);

        couponService.revoke(grantId, "误发", ADMIN);
        assertEquals(MallCouponGrantStatus.REVOKED, statusOf(grantId));
        assertMessage("已作废", () -> couponService.revoke(grantId, "再来一次", ADMIN));

        couponService.grant(couponId, grant("rid-rev2-" + System.nanoTime(), List.of(v), null), ADMIN);
        Long second = jdbc.queryForObject("SELECT MAX(id) FROM mall_coupon_grant WHERE coupon_id = ? AND volunteer_id = ?",
                Long.class, couponId, v);
        jdbc.update("UPDATE mall_coupon_grant SET status = ?, used_order_id = 1 WHERE id = ?",
                MallCouponGrantStatus.USED, second);
        // 已用在某张单上的卷作废了，那张单退回时就还不回去——账会对不上
        assertMessage("已被使用", () -> couponService.revoke(second, "想收回", ADMIN));
    }

    // ================= 我的卷 / 可用卷 =================

    @Test
    void myCouponsDerivesExpiredByTimeWithoutAnyJob() {
        Long couponId = couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 5), ADMIN);
        Long v = registered();
        couponService.grant(couponId, grant("rid-mine-" + System.nanoTime(), List.of(v), null), ADMIN);
        Long grantId = grantIdOf(couponId, v);
        // 模拟「时间走过了到期时刻」——库里的 status 仍是未使用，没有任何定时任务去改它
        jdbc.update("UPDATE mall_coupon_grant SET valid_start = ?, expire_time = ? WHERE id = ?",
                LocalDateTime.now().minusDays(5), LocalDateTime.now().minusMinutes(1), grantId);

        List<MallCouponGrantVO> expired = couponService.listMine(v, new PageQuery(), MallCouponGrantStatus.EXPIRED).getRecords();
        List<MallCouponGrantVO> usable = couponService.listMine(v, new PageQuery(), MallCouponGrantStatus.UNUSED).getRecords();
        List<MallCouponGrantVO> all = couponService.listMine(v, new PageQuery(), null).getRecords();

        assertEquals(1, expired.size());
        assertEquals(0, usable.size(), "过期的卷不能出现在「可用」里");
        assertEquals(MallCouponGrantStatus.EXPIRED, all.get(0).getStatus());
        assertEquals("已过期", all.get(0).getStatusLabel());
        assertEquals(MallCouponGrantStatus.UNUSED, statusOf(grantId), "「已过期」是派生态，绝不写回库里");
        assertTrue(couponService.listUsableForSpec(v, SPEC_A1).isEmpty());
    }

    @Test
    void usableForSpecUsesTheSameApplicabilityRuleAsOrdering() {
        Long v = registered();
        Long exchangeA = couponService.create(dto(MallCouponType.EXCHANGE, GOODS_A, null, null), ADMIN);
        Long storeWide = couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 5), ADMIN);
        Long onlyB = couponService.create(dto(MallCouponType.DISCOUNT, GOODS_B, 20, 5), ADMIN);
        for (Long c : List.of(exchangeA, storeWide, onlyB)) {
            couponService.grant(c, grant("rid-usable-" + c + "-" + System.nanoTime(), List.of(v), null), ADMIN);
        }

        assertEquals(List.of(exchangeA, storeWide).stream().sorted().toList(),
                couponIds(couponService.listUsableForSpec(v, SPEC_A1)),
                "标价 30：A 的兑换卷 + 全场满 20 减 5 可用；只限 B 的不可用");
        assertEquals(List.of(exchangeA), couponIds(couponService.listUsableForSpec(v, SPEC_A2)),
                "标价 8：够不着满 20 的门槛，只剩兑换卷");
        assertEquals(List.of(onlyB, storeWide).stream().sorted().toList(),
                couponIds(couponService.listUsableForSpec(v, SPEC_B1)));
    }

    @Test
    void goodsRequiringACouponOnlyAcceptsThatCoupon() {
        Long v = registered();
        Long required = couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 5), ADMIN);
        Long other = couponService.create(dto(MallCouponType.DISCOUNT, null, 20, 5), ADMIN);
        couponService.grant(required, grant("rid-req-" + System.nanoTime(), List.of(v), null), ADMIN);
        couponService.grant(other, grant("rid-req2-" + System.nanoTime(), List.of(v), null), ADMIN);
        jdbc.update("UPDATE mall_goods SET require_coupon_id = ? WHERE id = ?", required, GOODS_A);

        assertEquals(List.of(required), couponIds(couponService.listUsableForSpec(v, SPEC_A1)),
                "商品要求指定卷时，别的卷再合规也不能用");
    }

    @Test
    void adminListCarriesGrantAndUseCounts() {
        Long couponId = couponService.create(dto(MallCouponType.DISCOUNT, GOODS_A, 20, 5), ADMIN);
        Long v1 = registered();
        Long v2 = registered();
        couponService.grant(couponId, grant("rid-cnt-" + System.nanoTime(), List.of(v1, v2), null), ADMIN);
        jdbc.update("UPDATE mall_coupon_grant SET status = ?, used_order_id = 1 WHERE coupon_id = ? AND volunteer_id = ?",
                MallCouponGrantStatus.USED, couponId, v1);

        MallCouponVO vo = couponService.detailForAdmin(couponId);
        assertEquals(2L, vo.getGrantedCount());
        assertEquals(1L, vo.getUsedCount());
        assertEquals("保温杯", vo.getGoodsName());
        assertEquals(2, couponService.listGrants(couponId, new PageQuery(), null).getRecords().size());
        assertEquals(1, couponService.listGrants(couponId, new PageQuery(), MallCouponGrantStatus.USED).getRecords().size());
    }

    // ================= helpers =================

    private static MallCouponSaveDTO dto(int type, Long goodsId, Integer threshold, Integer discount) {
        MallCouponSaveDTO d = new MallCouponSaveDTO();
        d.setName("用例卷-" + System.nanoTime());
        d.setType(type);
        d.setGoodsId(goodsId);
        d.setThresholdPoints(threshold);
        d.setDiscountPoints(discount);
        d.setValidStart(LocalDateTime.now().minusDays(1));
        d.setValidEnd(LocalDateTime.now().plusDays(30));
        return d;
    }

    private static MallCouponGrantDTO grant(String rid, List<Long> ids, List<String> phones) {
        MallCouponGrantDTO d = new MallCouponGrantDTO();
        d.setRequestId(rid);
        d.setVolunteerIds(ids);
        d.setPhones(phones);
        return d;
    }

    private Long registered() {
        return insertVolunteer(nextPhone(), true, 0);
    }

    private static String nextPhone() {
        return String.format("139%08d", PHONE_SEQ.incrementAndGet() % 100_000_000L);
    }

    private Long insertVolunteer(String phone, boolean registered, int status) {
        Volunteer v = new Volunteer();
        v.setOpenid("openid_coupon_" + System.nanoTime());
        v.setRealName("卷用例");
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setStatus(status);
        v.setRegisterTime(registered ? LocalDateTime.now() : null);
        volunteerMapper.insert(v);
        return v.getId();
    }

    private void insertGoods(long id, String name, Long requireCouponId) {
        jdbc.update("INSERT INTO mall_goods (id, name, require_coupon_id, status, hidden, sort, create_time, update_time, "
                + "is_deleted) VALUES (?, ?, ?, ?, 0, 0, NOW(), NOW(), 0)", id, name, requireCouponId, MallGoodsStatus.ON_SALE);
    }

    private void insertSpec(long id, long goodsId, String name, int points) {
        jdbc.update("INSERT INTO mall_goods_spec (id, goods_id, name, points, stock, sort, create_time, update_time, "
                + "is_deleted) VALUES (?, ?, ?, ?, 5, 0, NOW(), NOW(), 0)", id, goodsId, name, points);
    }

    private int countGrants(Long couponId, Long volunteerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM mall_coupon_grant WHERE coupon_id = ? AND volunteer_id = ?",
                Integer.class, couponId, volunteerId);
    }

    private Long grantIdOf(Long couponId, Long volunteerId) {
        return jdbc.queryForObject("SELECT id FROM mall_coupon_grant WHERE coupon_id = ? AND volunteer_id = ?",
                Long.class, couponId, volunteerId);
    }

    private int statusOf(Long grantId) {
        return jdbc.queryForObject("SELECT status FROM mall_coupon_grant WHERE id = ?", Integer.class, grantId);
    }

    private int discountOf(Long volunteerId) {
        return jdbc.queryForObject("SELECT discount_points FROM mall_coupon_grant WHERE volunteer_id = ?",
                Integer.class, volunteerId);
    }

    private static List<Long> couponIds(List<MallCouponGrantVO> list) {
        return list.stream().map(MallCouponGrantVO::getCouponId).sorted().toList();
    }

    private static void assertMessage(String expectedFragment, org.junit.jupiter.api.function.Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertTrue(e.getMessage().contains(expectedFragment),
                "期望报错含「" + expectedFragment + "」，实际=「" + e.getMessage() + "」");
    }
}
