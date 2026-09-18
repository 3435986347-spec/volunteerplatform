package com.hengde.api.stress;

import com.fasterxml.jackson.databind.JsonNode;
import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动补全批的 <b>HTTP 全流程</b>（V4 活动补全批）——<b>必跑</b>。
 *
 * <p>后台建分队、发布「指定分队报名」的活动；分队成员报得上、外人被拒；审核、指派负责人、确认名单；
 * 志愿者端看公示：普通成员电话打 *、负责人全显示、<b>游客看负责人也打 *</b>（判断在控制器，只有 HTTP 测得到）；
 * 撤回之后看不到；志愿者 token 调后台接口被拒。</p>
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
class ActivityRosterApiFlowTest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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

    @Test
    void squadScopedEnrollment_thenRosterPublishedMaskedAndWithdrawn() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        long squad = ok(c.post("/a/organization/squads", admin,
                Map.of("name", "名单分队-" + tag, "type", "学校", "memberLimit", 0), "建分队")).dataAsLong();
        LocalDateTime start = LocalDateTime.now().plusDays(4).withHour(9).withMinute(0).withSecond(0).withNano(0);
        Map<String, Object> body = Map.of(
                "title", "名单公示活动-" + tag, "location", "雷州市西湖公园",
                "startTime", FMT.format(start), "endTime", FMT.format(start.plusHours(3)),
                "needAudit", 1, "enrollScope", 1, "targetSquadId", squad,
                "slots", List.of(Map.of("projectName", "上午场", "startTime", FMT.format(start),
                        "endTime", FMT.format(start.plusHours(2)), "needCount", 10)));
        long aid = ok(c.post("/a/activity/activities", admin, body, "发布限定分队的活动")).dataAsLong();
        Map<String, Object> wide = new java.util.HashMap<>(body);
        wide.put("enrollScope", 0);
        assertNotEquals(200, neg.post("/a/activity/activities", admin, wide, "全平台却填了分队").code());

        // ---- 报名：成员报得上，外人被拒 ----
        String leaderTok = c.devLogin("rl-" + tag);
        long leader = idOf(c, leaderTok);
        String memberTok = c.devLogin("rm-" + tag);
        long member = idOf(c, memberTok);
        String outsiderTok = c.devLogin("ro-" + tag);
        jdbc.update("UPDATE volunteer SET squad_id = ?, phone = ? WHERE id = ?", squad, cryptoUtil.encrypt("13512340001"), leader);
        jdbc.update("UPDATE volunteer SET squad_id = ?, phone = ? WHERE id = ?", squad, cryptoUtil.encrypt("13512340002"), member);

        JsonNode detail = ok(c.get("/v/activity/activities/" + aid, memberTok, "活动详情")).data();
        assertEquals("名单分队-" + tag, detail.path("targetSquadName").asText(), detail.toString());
        long slot = detail.get("slots").get(0).get("id").asLong();
        ok(c.post("/v/activity/activities/" + aid + "/enroll", leaderTok, Map.of("slotIds", List.of(slot)), "负责人报名"));
        ok(c.post("/v/activity/activities/" + aid + "/enroll", memberTok, Map.of("slotIds", List.of(slot)), "成员报名"));
        ApiStressClient.Resp rejected = neg.post("/v/activity/activities/" + aid + "/enroll", outsiderTok,
                Map.of("slotIds", List.of(slot)), "外人报名");
        assertNotEquals(200, rejected.code());
        assertTrue(String.valueOf(rejected.message()).contains("的成员报名"), rejected.message());

        // ---- 审核、指派负责人 ----
        for (JsonNode e : ok(c.get("/a/activity/activities/" + aid + "/enrollments?status=0&page=1&size=20", admin, "待审报名"))
                .data().get("records")) {
            ok(c.post("/a/activity/enrollments/" + e.get("enrollmentId").asText() + "/approve", admin, null, "审核通过"));
        }
        ok(c.post("/a/activity/activities/" + aid + "/leaders", admin, Map.of("leaderType", 1, "refId", leader), "指派负责人"));

        // ---- 名单公示 ----
        assertNotEquals(200, neg.get("/v/activity/rosters/" + aid, outsiderTok, "未确认名单").code());
        assertNotEquals(200, neg.post("/a/activity/activities/" + aid + "/roster/publish", memberTok, null, "志愿者 token 确认名单").code());
        ok(c.post("/a/activity/activities/" + aid + "/roster/publish", admin, null, "确认名单"));

        JsonNode seen = ok(c.get("/v/activity/rosters/" + aid, outsiderTok, "已实名的人看公示")).data();
        JsonNode members = seen.get("slots").get(0).get("members");
        assertEquals(2, members.size(), seen.toString());
        assertEquals("13512340001", phoneOf(members, true), "负责人全显示：" + members);
        assertEquals("135****0002", phoneOf(members, false), "普通成员打 *：" + members);
        assertEquals("13512340001", seen.get("leaders").get(0).get("phone").asText());

        String guest = ok(c.call("POST", "/v/auth/login/dev", null, Map.of("key", "rg-" + tag, "registered", false), "游客登录"))
                .data().get("token").asText();
        JsonNode guestSeen = ok(c.get("/v/activity/rosters/" + aid, guest, "游客看公示")).data();
        assertEquals("135****0001", phoneOf(guestSeen.get("slots").get(0).get("members"), true), "游客看负责人也打 *");
        assertEquals("135****0001", guestSeen.get("leaders").get(0).get("phone").asText());

        boolean listed = false;
        for (JsonNode r : ok(c.get("/v/activity/rosters?page=1&size=100", outsiderTok, "公示列表")).data().get("records")) {
            listed |= r.get("activityId").asLong() == aid;
        }
        assertTrue(listed, "公示列表里有这场");
        JsonNode preview = ok(c.get("/a/activity/activities/" + aid + "/roster", admin, "后台预览")).data();
        assertEquals("13512340002", phoneOf(preview.get("slots").get(0).get("members"), false), "后台预览全显示");

        ok(c.delete("/a/activity/activities/" + aid + "/roster/publish", admin, "撤回公示"));
        assertNotEquals(200, neg.get("/v/activity/rosters/" + aid, outsiderTok, "撤回之后").code());

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    private static long idOf(ApiStressClient c, String token) {
        return ok(c.get("/v/user/profile", token, "资料")).data().get("no").asLong();
    }

    private static String phoneOf(JsonNode members, boolean leader) {
        for (JsonNode m : members) {
            if (m.path("leader").asBoolean() == leader) {
                return m.path("phone").asText();
            }
        }
        throw new AssertionError("名单里没有 leader=" + leader + " 的人：" + members);
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
