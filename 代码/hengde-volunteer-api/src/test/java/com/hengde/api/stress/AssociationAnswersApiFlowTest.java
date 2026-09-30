package com.hengde.api.stress;

import com.fasterxml.jackson.databind.JsonNode;
import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.common.utils.RedisUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协会已答复、这一批才落到代码里的几件事——走真实 HTTP，<b>必跑</b>。
 *
 * <p>这几条的承重处都在服务层用例碰不到的地方：请求闸门（注销到期当场落定）、控制器里按登录态取权限
 * （处罚三档按部门拆的权限点）、按登录态取「我是不是这个分队的负责人」。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(classes = HengdeVolunteerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "hengde.auth.dev-login-enabled=true")
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class AssociationAnswersApiFlowTest {

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CryptoUtil cryptoUtil;
    @Autowired
    private RedisUtil redisUtil;

    /**
     * 注销：申请 → 冷静期内照常用、可撤销 → 到期后第一次请求就被闸门当场落定（403、手机号释放）→
     * 只有持 user:restore（理事会）的账号能恢复，恢复时换一个号。
     */
    @Test
    void deactivation_coolingOff_gateFinalizes_thenCouncilRestores() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        String key = "deact-" + tag;
        String me = c.devLogin(key);
        long vid = jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:" + key);
        String phone = bindPhone(vid);

        ok(c.post("/v/auth/sms/codes", null, Map.of("phone", phone, "scene", "account-deactivate"), "发注销验证码"));
        JsonNode applied = ok(c.post("/v/user/deactivation", me, Map.of("smsCode", code("account-deactivate", phone)), "申请注销")).data();
        assertTrue(applied.get("pending").asBoolean(), applied.toString());
        assertEquals(7, applied.get("coolingDays").asInt());

        // 冷静期内照常可用，可以撤销
        assertTrue(ok(c.get("/v/user/profile", me, "冷静期内看资料")).data().hasNonNull("deactivateEffectiveTime"));
        ok(c.delete("/v/user/deactivation", me, "撤销注销"));
        assertFalse(ok(c.get("/v/user/deactivation", me, "撤销后")).data().get("pending").asBoolean());

        // 再申请一次，然后把冷静期挪到昨天：下一次请求被闸门拦下并当场落定
        ok(c.post("/v/auth/sms/codes", null, Map.of("phone", phone, "scene", "account-deactivate"), "再发验证码"));
        ok(c.post("/v/user/deactivation", me, Map.of("smsCode", code("account-deactivate", phone)), "再申请"));
        jdbc.update("UPDATE volunteer SET deactivate_effective_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id = ?", vid);
        ApiStressClient.Resp denied = neg.get("/v/user/profile", me, "到期后的请求");
        assertEquals(403, denied.code(), "到期即注销：" + denied);
        assertTrue(denied.message().contains("注销"), denied.message());
        Map<String, Object> row = jdbc.queryForMap("SELECT status, phone_hash, openid FROM volunteer WHERE id = ?", vid);
        assertEquals(2, ((Number) row.get("status")).intValue());
        assertEquals(null, row.get("phone_hash"), "手机号释放");
        assertTrue(row.get("openid").toString().startsWith("deactivated:"));

        // 恢复：没有 user:restore 的子账号不行；超管（理事会同等权限）换一个号恢复
        long listPerm = permId(c, root, "user:list");
        String noRestore = subAccount(c, root, "nr" + tag, "组织部", listPerm);
        assertTrue(neg.post("/a/user/volunteers/" + vid + "/restore", noRestore,
                Map.of("phone", "13999990000"), "无权恢复").code() != 200, "没有 user:restore 恢复不了");
        String council = subAccount(c, root, "rs" + tag, "理事会", permId(c, root, "user:restore"));
        String newPhone = freePhone();
        ok(c.post("/a/user/volunteers/" + vid + "/restore", council, Map.of("phone", newPhone), "理事会恢复"));
        Map<String, Object> back = jdbc.queryForMap("SELECT status, phone_hash, restore_by FROM volunteer WHERE id = ?", vid);
        assertEquals(0, ((Number) back.get("status")).intValue());
        assertEquals(cryptoUtil.hashPhone(newPhone), back.get("phone_hash"));
    }

    /** 处罚三档按部门各一个权限点（协会 2026-09-16），判定在控制器——只有 HTTP 用例证明得了它真的接上了登录态。 */
    @Test
    void sanctionScopes_needTheirOwnDepartmentPermission() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        c.devLogin("rp-" + tag);
        long vid = jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:rp-" + tag);

        long open = permId(c, root, "honor:reward-punish");
        String orgDept = subAccount(c, root, "og" + tag, "组织部", open, permId(c, root, "honor:sanction-activity"));
        String pubDept = subAccount(c, root, "pb" + tag, "宣传部", open, permId(c, root, "honor:sanction-community"));

        assertEquals(200, neg.post("/a/honor/reward-punishes", orgDept, punish(vid, 1), "组织部开限制参加活动").code());
        assertEquals(403, neg.post("/a/honor/reward-punishes", orgDept, punish(vid, 2), "组织部开限制发布社区").code());
        assertEquals(403, neg.post("/a/honor/reward-punishes", orgDept, punish(vid, 3), "组织部开拒绝使用本程序").code());
        assertEquals(200, neg.post("/a/honor/reward-punishes", pubDept, punish(vid, 2), "宣传部开限制发布社区").code());
        assertEquals(403, neg.post("/a/honor/reward-punishes", pubDept, punish(vid, 1), "宣传部开限制参加活动").code());
        assertEquals(200, neg.post("/a/honor/reward-punishes", pubDept, punish(vid, null), "不附带处置的单谁都能开").code());
    }

    /** 分队负责人在小程序里审加入申请（协会 2026-09-02：分队自己审，也可以由部门审核）。 */
    /**
     * 提前解除处置与开出同一档要同一个权限点：光有 honor:sanction 解不了别的部门开的处置。
     * 静态判定的用例证明不了控制器真的在解除前取了这张单的档位——这里走真实 HTTP。
     */
    @Test
    void liftingASanction_needsTheSameDepartmentPermissionAsImposingIt() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        c.devLogin("lift-" + tag);
        long vid = jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:lift-" + tag);
        // 超管持终审权：开即通过、处置当场生效
        long ticket = ok(c.post("/a/honor/reward-punishes", root, punish(vid, 3), "开拒绝使用本程序")).dataAsLong();

        long lift = permId(c, root, "honor:sanction");
        String orgDept = subAccount(c, root, "lo" + tag, "组织部", lift, permId(c, root, "honor:sanction-activity"));
        String secDept = subAccount(c, root, "ls" + tag, "秘书部", lift, permId(c, root, "honor:sanction-all"));

        Map<String, Object> reason = Map.of("reason", "测试提前解除");
        assertEquals(403, neg.post("/a/honor/reward-punishes/" + ticket + "/lift-sanction", orgDept, reason,
                "组织部解秘书部开的").code());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM volunteer_sanction WHERE volunteer_id = ? "
                + "AND status = 1", Integer.class, vid), "处置还在");
        assertEquals(200, neg.post("/a/honor/reward-punishes/" + ticket + "/lift-sanction", secDept, reason,
                "秘书部解自己那一档").code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM volunteer_sanction WHERE volunteer_id = ? "
                + "AND status = 1", Integer.class, vid), "秘书部解掉了");
    }

    @Test
    void squadLeader_reviewsJoinApplications_throughTheVolunteerApi() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        String leader = c.devLogin("sl-" + tag);
        long leaderId = jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:sl-" + tag);
        String applicant = c.devLogin("sa-" + tag);
        String outsider = c.devLogin("so-" + tag);

        Map<String, Object> squad = new HashMap<>();
        squad.put("name", "负责人审核分队-" + tag);
        squad.put("type", "学校分队");
        squad.put("leaderId", leaderId);
        squad.put("memberLimit", 0);
        squad.put("status", 1);
        long squadId = ok(c.post("/a/organization/squads", root, squad, "建分队")).dataAsLong();
        long appId = ok(c.post("/v/organization/squads/" + squadId + "/applications", applicant,
                Map.of("reason", "想加入"), "申请加入")).dataAsLong();

        JsonNode led = ok(c.get("/v/organization/squads/led", leader, "我负责的分队")).data();
        assertEquals(squadId, led.get(0).get("id").asLong(), led.toString());
        JsonNode pending = ok(c.get("/v/organization/squads/" + squadId + "/applications", leader, "待审")).data().get("records");
        assertEquals(appId, pending.get(0).get("id").asLong());
        assertTrue(neg.get("/v/organization/squads/" + squadId + "/applications", outsider, "外人看").code() != 200,
                "不是这个分队的负责人，看不到本分队的申请");
        assertTrue(neg.post("/v/organization/squads/applications/" + appId + "/approve", outsider, null, "外人批").code() != 200,
                "也批不了");

        ok(c.post("/v/organization/squads/applications/" + appId + "/approve", leader, null, "负责人批准"));
        Map<String, Object> row = jdbc.queryForMap("SELECT status, audit_by, audit_by_type FROM squad_application WHERE id = ?", appId);
        assertEquals(1, ((Number) row.get("status")).intValue());
        assertEquals(leaderId, ((Number) row.get("audit_by")).longValue());
        assertEquals(2, ((Number) row.get("audit_by_type")).intValue());
    }

    /** 每年确认学校年级：确认后本学年不再提示。 */
    @Test
    void schoolConfirm_throughTheVolunteerApi() {
        ApiStressClient c = new ApiStressClient(port);
        String tag = Long.toString(System.nanoTime(), 36);
        String me = c.devLogin("sc-" + tag);
        long vid = jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:sc-" + tag);
        jdbc.update("UPDATE volunteer SET school_confirm_year = 2000 WHERE id = ?", vid);
        assertTrue(ok(c.get("/v/user/profile", me, "资料")).data().get("schoolConfirmPending").asBoolean());
        ok(c.post("/v/user/profile/school-confirm", me, Map.of("school", "雷州一中", "grade", 11), "确认"));
        JsonNode after = ok(c.get("/v/user/profile", me, "确认后")).data();
        assertFalse(after.get("schoolConfirmPending").asBoolean(), after.toString());
        assertEquals("雷州一中", after.get("school").asText());
    }

    // ---------------- helpers ----------------

    private static Map<String, Object> punish(long vid, Integer scope) {
        Map<String, Object> body = new HashMap<>();
        body.put("volunteerId", vid);
        body.put("type", 2);
        body.put("category", "违规");
        body.put("title", "测试处罚-" + System.nanoTime());
        if (scope != null) {
            body.put("sanctionScope", scope);
            body.put("sanctionDays", 7);
        }
        return body;
    }

    private String bindPhone(long vid) {
        String phone = freePhone();
        jdbc.update("UPDATE volunteer SET phone = ?, phone_hash = ? WHERE id = ?",
                cryptoUtil.encrypt(phone), cryptoUtil.hashPhone(phone), vid);
        return phone;
    }

    private static String freePhone() {
        return "137" + String.format("%08d", System.nanoTime() % 100_000_000L);
    }

    private String code(String scene, String phone) {
        Object v = redisUtil.get("sms:code:" + scene + ":" + phone);
        return v == null ? null : v.toString();
    }

    private long permId(ApiStressClient c, String root, String code) {
        for (JsonNode p : ok(c.get("/a/organization/permissions", root, "权限点")).data()) {
            if (code.equals(p.get("code").asText())) {
                return p.get("id").asLong();
            }
        }
        throw new AssertionError("权限点不存在：" + code);
    }

    private String subAccount(ApiStressClient c, String root, String username, String department, long... perms) {
        long accountId = ok(c.post("/a/organization/sub-accounts", root, Map.of("username", username,
                "password", "pass1234", "realName", department + "测试", "department", department), "建子账号")).dataAsLong();
        List<Long> ids = new java.util.ArrayList<>();
        for (long p : perms) {
            ids.add(p);
        }
        ok(c.put("/a/organization/sub-accounts/" + accountId + "/permissions", root, Map.of("permissionIds", ids), "授权"));
        return c.adminLogin(username, "pass1234");
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
