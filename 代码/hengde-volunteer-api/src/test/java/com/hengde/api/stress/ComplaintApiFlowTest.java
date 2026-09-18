package com.hengde.api.stress;

import com.fasterxml.jackson.databind.JsonNode;
import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投诉建议的 <b>HTTP 全流程</b>（V4 投诉建议批）——<b>必跑</b>。
 *
 * <p>服务层用例证明不了的是「范围怎么算出来」：控制器按登录态查部门、按权限点判全部门——
 * 这里真建两个子账号（监察部 / 宣传部，都只给 {@code data:complaint}），真登录，真走一遍
 * 提交 → 监察部受理 → 流转宣传部 → 宣传部答复，并断言工单转走之后监察部的账号在列表里看不到、按 id 也动不了；
 * 超管（通配权限）看全部。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(classes = HengdeVolunteerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hengde.auth.dev-login-enabled=true", "hengde.data.complaint.default-department=监察部"})
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class ComplaintApiFlowTest {

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;

    @Test
    void submitAcceptTransferReply_withDepartmentScopesComputedFromLogin() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        long permId = -1;
        for (JsonNode p : ok(c.get("/a/organization/permissions", root, "权限点列表")).data()) {
            if ("data:complaint".equals(p.get("code").asText())) {
                permId = p.get("id").asLong();
            }
        }
        assertTrue(permId > 0, "V65 种了 data:complaint");
        String supervisor = handler(c, root, "cs" + tag, "监察部", permId);
        String publicity = handler(c, root, "cp" + tag, "宣传部", permId);

        // ---- 志愿者提交 ----
        String me = c.devLogin("complaint-" + tag);
        long id = ok(c.post("/v/data/complaints", me, Map.of("type", 1, "content", "活动集合点没有指示牌-" + tag),
                "提交投诉")).dataAsLong();
        JsonNode mine = ok(c.get("/v/data/complaints/" + id, me, "我的工单")).data();
        assertEquals("监察部", mine.get("currentDepartment").asText());

        // ---- 监察部受理并流转 ----
        assertTrue(listed(c, supervisor, id), "监察部的列表里有它");
        assertFalse(listed(c, publicity, id), "还没转过去，宣传部的列表里没有");
        ok(c.post("/a/data/complaints/" + id + "/accept", supervisor, null, "监察部受理"));
        JsonNode depts = ok(c.get("/a/data/complaints/departments", supervisor, "可流转部门")).data();
        assertTrue(depts.toString().contains("宣传部"), depts.toString());
        ok(c.post("/a/data/complaints/" + id + "/transfer", supervisor,
                Map.of("department", "宣传部", "reason", "宣传部负责现场布置"), "流转宣传部"));
        assertFalse(listed(c, supervisor, id), "转走之后监察部看不到");
        assertNotEquals(200, neg.post("/a/data/complaints/" + id + "/reply", supervisor,
                Map.of("content", "越权答复"), "监察部越权答复").code());

        // ---- 宣传部答复；超管看得到全部进度 ----
        ok(c.post("/a/data/complaints/" + id + "/reply", publicity, Map.of("content", "已补设指示牌，感谢反馈"), "宣传部答复"));
        JsonNode done = ok(c.get("/v/data/complaints/" + id, me, "看答复")).data();
        assertEquals(2, done.get("status").asInt());
        assertEquals("已补设指示牌，感谢反馈", done.get("replyContent").asText());
        assertFalse(done.toString().contains("宣传部负责现场布置"), "流转理由不下发给志愿者：" + done);
        JsonNode full = ok(c.get("/a/data/complaints/" + id, root, "超管看详情")).data();
        assertEquals(4, full.get("progress").size());
        assertTrue(full.toString().contains("宣传部负责现场布置"));
        assertTrue(ok(c.get("/v/data/complaints/mine?page=1&size=10", me, "我的工单列表")).data().get("records").size() >= 1);

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());

        // ---- 负向 ----
        assertNotEquals(200, neg.get("/a/data/complaints", me, "志愿者 token 调后台").code());
        String stranger = neg.devLogin("complaint-stranger-" + tag);
        assertNotEquals(200, neg.get("/v/data/complaints/" + id, stranger, "看别人的工单").code());
        String noPerm = handler(c, root, "cn" + tag, "监察部", -1);
        assertNotEquals(200, neg.get("/a/data/complaints", noPerm, "没有权限点的子账号").code());
    }

    private String handler(ApiStressClient c, String root, String username, String department, long permId) {
        long accountId = ok(c.post("/a/organization/sub-accounts", root, Map.of("username", username,
                "password", "pass1234", "realName", department + "处理人", "department", department), "建子账号")).dataAsLong();
        if (permId > 0) {
            ok(c.put("/a/organization/sub-accounts/" + accountId + "/permissions", root,
                    Map.of("permissionIds", List.of(permId)), "授权"));
        }
        return c.adminLogin(username, "pass1234");
    }

    private boolean listed(ApiStressClient c, String token, long id) {
        for (JsonNode r : ok(c.get("/a/data/complaints?page=1&size=100", token, "工单列表")).data().get("records")) {
            if (r.get("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
