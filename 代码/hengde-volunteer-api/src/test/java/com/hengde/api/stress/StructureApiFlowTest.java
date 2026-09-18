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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组织架构维护的 <b>HTTP 全流程</b>（V4 组织架构维护批）——<b>必跑</b>。
 *
 * <p>后台建部门、放人、挪环被拒、删有人的部门被拒；志愿者端看树：<b>已实名的人看得到电话、游客看不到</b>
 * （这一条只有走 HTTP 才测得到——判断在控制器里）；本人资料与志愿者管理列表里名字下面那一行跟着架构走；
 * 志愿者 token 调后台接口被拒。</p>
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
class StructureApiFlowTest {

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
    void maintainTheTree_placePeople_andPhoneOnlyForRegisteredViewers() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        long root = ok(c.get("/a/organization/structure", admin, "架构树")).data().get(0).get("id").asLong();
        String deptName = "接口部-" + tag;
        long dept = ok(c.post("/a/organization/structure/nodes", admin,
                Map.of("parentId", root, "name", deptName, "sort", 50), "建部门")).dataAsLong();
        long child = ok(c.post("/a/organization/structure/nodes", admin,
                Map.of("parentId", dept, "name", "接口组-" + tag), "建下级")).dataAsLong();
        assertNotEquals(200, neg.post("/a/organization/structure/nodes", admin, Map.of("parentId", dept, "name", " "), "空名称").code());
        assertNotEquals(200, neg.put("/a/organization/structure/nodes/" + dept, admin,
                Map.of("parentId", child, "name", deptName), "挂到自己的下级下面").code());

        // ---- 放人：一位有电话的志愿者 ----
        String memberToken = c.devLogin("sm-" + tag);
        long memberId = ok(c.get("/v/user/profile", memberToken, "成员资料")).data().get("no").asLong();
        String phone = "137" + String.format("%08d", Math.floorMod(System.nanoTime(), 100_000_000L));
        jdbc.update("UPDATE volunteer SET phone = ? WHERE id = ?", cryptoUtil.encrypt(phone), memberId);
        long place = ok(c.post("/a/organization/structure/nodes/" + dept + "/members", admin,
                Map.of("volunteerId", memberId, "position", "部长"), "放人")).dataAsLong();
        assertNotEquals(200, neg.post("/a/organization/structure/nodes/" + child + "/members", admin,
                Map.of("volunteerId", memberId, "position", "干事"), "同一人放第二个位置").code());

        JsonNode adminMember = memberOf(ok(c.get("/a/organization/structure", admin, "后台看树")).data(), dept);
        assertEquals("部长", adminMember.get("position").asText());
        assertEquals(phone, adminMember.path("phone").asText(), "后台看得到电话：" + adminMember);

        // ---- 志愿者端：已实名看得到电话，游客看不到 ----
        String viewer = c.devLogin("sv-" + tag);
        JsonNode seen = memberOf(ok(c.get("/v/organization/structure", viewer, "志愿者看树")).data(), dept);
        assertEquals(phone, seen.path("phone").asText(), "已实名的志愿者看得到电话：" + seen);
        String guest = ok(c.call("POST", "/v/auth/login/dev", null,
                Map.of("key", "sg-" + tag, "registered", false), "游客登录")).data().get("token").asText();
        JsonNode guestSeen = memberOf(ok(c.get("/v/organization/structure", guest, "游客看树")).data(), dept);
        assertEquals("部长", guestSeen.get("position").asText(), "游客照样看得到架构与职位");
        assertTrue(guestSeen.path("phone").isMissingNode() || guestSeen.path("phone").isNull(), "游客拿不到电话：" + guestSeen);

        // ---- 名字下面那一行 ----
        assertEquals(deptName + " · 部长", ok(c.get("/v/user/profile", memberToken, "成员资料")).data().path("position").asText());
        ok(c.put("/a/organization/structure/members/" + place, admin, Map.of("nodeId", child, "position", "组长"), "挪人"));
        JsonNode rows = ok(c.get("/a/user/volunteers?page=1&size=20&keyword=sm-" + tag, admin, "志愿者管理")).data().get("records");
        JsonNode row = null;
        for (JsonNode r : rows) {
            if (r.get("id").asLong() == memberId) {
                row = r;
            }
        }
        assertNotNull(row, "志愿者管理里找得到这个人：" + rows);
        assertEquals("接口组-" + tag + " · 组长", row.path("position").asText());

        // ---- 权限与删除守卫 ----
        assertNotEquals(200, neg.post("/a/organization/structure/nodes", memberToken,
                Map.of("parentId", root, "name", "志愿者建的"), "志愿者 token 调后台").code());
        ApiStressClient.Resp blocked = neg.delete("/a/organization/structure/nodes/" + child, admin, "删有人的节点");
        assertNotEquals(200, blocked.code());
        assertTrue(String.valueOf(blocked.message()).contains("还有 1 个人"), blocked.message());
        ok(c.delete("/a/organization/structure/members/" + place, admin, "移出"));
        ok(c.delete("/a/organization/structure/nodes/" + child, admin, "删下级"));
        ok(c.delete("/a/organization/structure/nodes/" + dept, admin, "删部门"));
        assertFalse(ok(c.get("/v/user/profile", memberToken, "成员资料")).data().has("position"), "移出后名字下面那一行消失");

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    private static JsonNode memberOf(JsonNode roots, long nodeId) {
        Map<Long, JsonNode> byId = new HashMap<>();
        collect(roots, byId);
        JsonNode node = byId.get(nodeId);
        assertNotNull(node, "树里找得到节点 " + nodeId);
        JsonNode members = node.get("members");
        assertEquals(1, members.size(), node.toString());
        return members.get(0);
    }

    private static void collect(JsonNode nodes, Map<Long, JsonNode> out) {
        for (JsonNode n : nodes) {
            out.put(n.get("id").asLong(), n);
            collect(n.get("children"), out);
        }
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
