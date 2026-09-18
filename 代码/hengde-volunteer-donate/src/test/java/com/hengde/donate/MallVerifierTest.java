package com.hengde.donate;

import com.hengde.activity.constant.PointSourceType;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.constant.MallOrderStatus;
import com.hengde.donate.constant.PickupOperatorType;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.service.MallOrderService;
import com.hengde.donate.service.MallVerifierService;
import com.hengde.donate.vo.MallOrderVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核销员（Row 8 F「企业可以设置某一个志愿者为企业核销员，企业核销员可以在前端用扫一扫功能核销」）。
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class MallVerifierTest {

    private static final long ADMIN = 7201L;
    private static final long GOODS_ID = 995_001L;
    private static final long SPEC_ID = 995_101L;
    private static final AtomicLong PHONE_SEQ = new AtomicLong(System.nanoTime() % 10_000_000L + 40_000_000L);

    @Autowired
    private MallVerifierService verifierService;
    @Autowired
    private MallOrderService orderService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private VolunteerMapper volunteerMapper;
    @Autowired
    private CryptoUtil cryptoUtil;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM mall_goods_spec WHERE goods_id = ?", GOODS_ID);
        jdbc.update("DELETE FROM mall_goods WHERE id = ?", GOODS_ID);
        // 商品名必须本类独有：「全部兑换记录」是全库公开列表，MallPickupAndReviewTest 按商品名「帆布袋」
        // 数其中的条数——这里曾经也叫「帆布袋」，于是那条用例在同一个容器里数到了本类造的单（1 → 6）
        jdbc.update("INSERT INTO mall_goods (id, name, status, hidden, sort, create_time, update_time, is_deleted) "
                + "VALUES (?, '核销用例水杯', ?, 0, 0, NOW(), NOW(), 0)", GOODS_ID, MallGoodsStatus.ON_SALE);
        jdbc.update("INSERT INTO mall_goods_spec (id, goods_id, name, points, stock, sort, create_time, update_time, "
                + "is_deleted) VALUES (?, ?, '标准', 10, 10, 0, NOW(), NOW(), 0)", SPEC_ID, GOODS_ID);
    }

    @Test
    void onlyRegisteredActiveVolunteersCanBeVerifiers() {
        Long guest = insertVolunteer(false, 0);
        Long banned = insertVolunteer(true, 1);
        assertMessage("已实名且账号正常", () -> verifierService.assign(guest, null, ADMIN));
        assertMessage("已实名且账号正常", () -> verifierService.assign(banned, null, ADMIN));

        Long ok = insertVolunteer(true, 0);
        Long id = verifierService.assign(ok, "协会办公室", ADMIN);
        assertTrue(verifierService.isVerifier(ok));
        assertMessage("已是核销员", () -> verifierService.assign(ok, null, ADMIN));

        verifierService.remove(id);
        assertFalse(verifierService.isVerifier(ok));
        // 软删行不占 uk_active_volunteer：撤掉之后必须能再指派回来，否则界面上彻底死锁（V31 同一课）
        verifierService.assign(ok, "重新指派", ADMIN);
        assertTrue(verifierService.isVerifier(ok));
        assertTrue(verifierService.list(new PageQuery()).getRecords().stream()
                .anyMatch(v -> v.getVolunteerId().equals(ok) && "重新指派".equals(v.getRemark())));
    }

    @Test
    void verifierScansCodeAndOperatorTypeIsRecorded() {
        Long verifier = insertVolunteer(true, 0);
        verifierService.assign(verifier, null, ADMIN);
        Long buyer = insertVolunteer(true, 0);
        String code = approvedOrderCode(buyer);

        MallOrderVO vo = orderService.verifyByVerifier(code, verifier);

        assertEquals(MallOrderStatus.PICKED, vo.getStatus());
        assertEquals("核销用例", vo.getVolunteerName(), "柜台要看到这是谁的单");
        assertEquals(verifier, jdbc.queryForObject("SELECT pickup_operator FROM mall_order WHERE pickup_code = ?",
                Long.class, code));
        assertEquals(PickupOperatorType.VERIFIER, jdbc.queryForObject(
                "SELECT pickup_operator_type FROM mall_order WHERE pickup_code = ?", Integer.class, code),
                "核销人 id 一列装两类账号，不带类型就分不清是管理员还是志愿者");
        assertMessage("核销过", () -> orderService.verifyByVerifier(code, verifier));
    }

    @Test
    void adminVerificationIsTypedAsAdmin() {
        Long buyer = insertVolunteer(true, 0);
        String code = approvedOrderCode(buyer);

        orderService.verify(code, ADMIN);

        assertEquals(PickupOperatorType.ADMIN, jdbc.queryForObject(
                "SELECT pickup_operator_type FROM mall_order WHERE pickup_code = ?", Integer.class, code));
    }

    @Test
    void nonVerifierIsRefused() {
        Long stranger = insertVolunteer(true, 0);
        Long buyer = insertVolunteer(true, 0);
        String code = approvedOrderCode(buyer);

        assertMessage("不是核销员", () -> orderService.verifyByVerifier(code, stranger));
        assertEquals(MallOrderStatus.READY, jdbc.queryForObject(
                "SELECT status FROM mall_order WHERE pickup_code = ?", Integer.class, code));
    }

    @Test
    void aBannedVerifierLosesThePowerImmediately() {
        Long verifier = insertVolunteer(true, 0);
        verifierService.assign(verifier, null, ADMIN);
        jdbc.update("UPDATE volunteer SET status = 1 WHERE id = ?", verifier);
        Long buyer = insertVolunteer(true, 0);
        String code = approvedOrderCode(buyer);

        // 记录还在，但账号停用即失效——与志愿者 RBAC「降级即失效」同一口径
        assertMessage("不是核销员", () -> orderService.verifyByVerifier(code, verifier));
    }

    @Test
    void aVerifierCannotVerifyTheirOwnOrder() {
        Long verifier = insertVolunteer(true, 0);
        verifierService.assign(verifier, null, ADMIN);
        String code = approvedOrderCode(verifier);

        assertMessage("不能核销自己的兑换单", () -> orderService.verifyByVerifier(code, verifier));
    }

    /**
     * 两个窗口同时扫同一个码：输家必须被告知「已核销过」，而不是「当前不可领取（待领取）」。
     *
     * <p><b>怎么把时序摆出来</b>：一条裸 JDBC 事务先锁住那一行（不改状态），输家随后进入——它会先做普通读
     * （查核销员资格、预查目标单，读视图就定在此刻），然后卡在 CAS 上；裸事务再把状态改成已领取并提交。
     * 输家的 CAS 返回 0，此时它的复核若是快照读，看到的仍是「待领取」。</p>
     *
     * <p>变异验证：把 {@code MallOrderService.doVerify} 里的 {@code selectByPickupCodeForShare} 换回普通
     * {@code selectOne}，本用例以「当前不可领取（待领取）」变红。</p>
     */
    @Test
    void theLosingVerifierIsToldItWasAlreadyPickedUp() throws Exception {
        Long verifier = insertVolunteer(true, 0);
        verifierService.assign(verifier, null, ADMIN);
        Long buyer = insertVolunteer(true, 0);
        String code = approvedOrderCode(buyer);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            try (PreparedStatement lock = winner.prepareStatement(
                    "UPDATE mall_order SET update_time = NOW() WHERE pickup_code = ?")) {
                lock.setString(1, code);
                assertEquals(1, lock.executeUpdate());
            }
            Future<String> loser = pool.submit(() -> {
                try {
                    orderService.verifyByVerifier(code, verifier);
                    return "OK";
                } catch (BusinessException e) {
                    return e.getMessage();
                }
            });
            Thread.sleep(800);   // 输家此时已建好读视图，并卡在 CAS 上等这一行
            try (PreparedStatement pick = winner.prepareStatement(
                    "UPDATE mall_order SET status = ?, pickup_time = NOW(), pickup_operator = ?, pickup_operator_type = ? "
                            + "WHERE pickup_code = ? AND status = ?")) {
                pick.setInt(1, MallOrderStatus.PICKED);
                pick.setLong(2, ADMIN);
                pick.setInt(3, PickupOperatorType.ADMIN);
                pick.setString(4, code);
                pick.setInt(5, MallOrderStatus.READY);
                assertEquals(1, pick.executeUpdate());
            }
            winner.commit();

            String message = loser.get(30, TimeUnit.SECONDS);
            assertTrue(message.contains("核销过"),
                    "输家的读视图建在赢家提交之前，复核若是快照读会报「当前不可领取（待领取）」——实际：" + message);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- helpers ----------------

    private String approvedOrderCode(Long buyer) {
        jdbc.update("INSERT INTO point_record (volunteer_id, change_amount, source_type, source_id, remark, "
                + "operator_type, create_time, update_time, is_deleted) VALUES (?, 50, ?, NULL, '用例预置', 0, NOW(), NOW(), 0)",
                buyer, PointSourceType.MANUAL);
        MallOrder order = orderService.placeOrder(buyer, SPEC_ID);
        orderService.approve(order.getId(), ADMIN);
        return jdbc.queryForObject("SELECT pickup_code FROM mall_order WHERE id = ?", String.class, order.getId());
    }

    private Long insertVolunteer(boolean registered, int status) {
        String phone = String.format("135%08d", PHONE_SEQ.incrementAndGet() % 100_000_000L);
        Volunteer v = new Volunteer();
        v.setOpenid("openid_verifier_" + System.nanoTime());
        v.setRealName("核销用例");
        v.setPhone(cryptoUtil.encrypt(phone));
        v.setPhoneHash(cryptoUtil.hashPhone(phone));
        v.setStatus(status);
        v.setRegisterTime(registered ? LocalDateTime.now() : null);
        volunteerMapper.insert(v);
        return v.getId();
    }

    private static void assertMessage(String expectedFragment, org.junit.jupiter.api.function.Executable call) {
        BusinessException e = assertThrows(BusinessException.class, call);
        assertTrue(e.getMessage().contains(expectedFragment),
                "期望报错含「" + expectedFragment + "」，实际=「" + e.getMessage() + "」");
    }
}
