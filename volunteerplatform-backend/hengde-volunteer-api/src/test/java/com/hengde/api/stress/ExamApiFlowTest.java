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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动临时负责人考试的 <b>HTTP 全流程</b>（V4 临时负责人考试批）——<b>必跑</b>。
 *
 * <p>真建三个子账号，分别只授 {@code org:exam}（出题）/ {@code org:exam-grade}（阅卷）/ {@code org:temp-leader}（管名单），真登录走一遍：
 * 出题开放 → 志愿者看题（不含答案）交卷 → 阅卷人出分 → 志愿者成为临时负责人（「我的」所属职务跟着变）→ 名单与 xlsx 导出 → 撤销。
 * 并断言三个权限点各管各的：出题人阅不了卷、阅卷人看不了名单、志愿者 token 调不了后台。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(classes = HengdeVolunteerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hengde.auth.dev-login-enabled=true"})
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class ExamApiFlowTest {

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;

    @Test
    void setPaper_takeExam_grade_becomeTempLeader_exportAndRevoke() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        Map<String, Long> perms = new HashMap<>();
        for (JsonNode p : ok(c.get("/a/organization/permissions", root, "权限点列表")).data()) {
            perms.put(p.get("code").asText(), p.get("id").asLong());
        }
        assertTrue(perms.containsKey("org:exam") && perms.containsKey("org:exam-grade") && perms.containsKey("org:temp-leader"),
                "V75 种了三个权限点");
        String setter = account(c, root, "es" + tag, perms.get("org:exam"));
        String grader = account(c, root, "eg" + tag, perms.get("org:exam-grade"));
        String keeper = account(c, root, "et" + tag, perms.get("org:temp-leader"));

        // ---- 出题并开放（先停掉别的开放中的试卷：同一时刻只能有一份）----
        for (JsonNode p : ok(c.get("/a/organization/exam-papers?status=1&page=1&size=100", setter, "开放中的试卷")).data().get("records")) {
            ok(c.post("/a/organization/exam-papers/" + p.get("id").asLong() + "/close", setter, null, "停掉旧试卷"));
        }
        List<Map<String, Object>> qs = new ArrayList<>();
        qs.add(Map.of("type", 1, "title", "集合迟到应当", "options", List.of("不管", "联系负责人"), "score", 30, "answer", "B"));
        qs.add(Map.of("type", 2, "title", "签到前确认", "options", List.of("人数", "天气", "集合点"), "score", 30, "answer", List.of("C", "A")));
        qs.add(Map.of("type", 3, "title", "负责人可以提前离场", "score", 10, "answer", false));
        qs.add(Map.of("type", 5, "title", "说说怎么组织签到", "score", 30, "answer", "先点名再核对名单"));
        long paperId = ok(c.post("/a/organization/exam-papers", setter, Map.of("title", "临时负责人考试-" + tag,
                "passScore", 60, "qualificationMonths", 12, "questions", qs), "出题")).dataAsLong();
        ok(c.post("/a/organization/exam-papers/" + paperId + "/publish", setter, null, "开放"));
        JsonNode full = ok(c.get("/a/organization/exam-papers/" + paperId, grader, "阅卷人看试卷（含标准答案）")).data();
        assertEquals("人数、集合点", full.get("questions").get(1).get("answerDisplay").asText());

        // ---- 志愿者考试 ----
        String me = c.devLogin("exam-" + tag);
        JsonNode current = ok(c.get("/v/organization/exams/current", me, "我的考试")).data();
        assertTrue(current.get("canTake").asBoolean(), current.toString());
        assertEquals(paperId, current.get("paper").get("id").asLong());
        JsonNode questions = current.get("paper").get("questions");
        assertFalse(questions.toString().contains("\"answer\""), "志愿者端不下发标准答案：" + questions);
        List<Map<String, Object>> answers = List.of(
                Map.of("questionId", questions.get(0).get("id").asLong(), "value", "B"),
                Map.of("questionId", questions.get(1).get("id").asLong(), "value", List.of("A", "C")),
                Map.of("questionId", questions.get(2).get("id").asLong(), "value", true),
                Map.of("questionId", questions.get(3).get("id").asLong(), "value", "先点名，再按名单核对人数"));
        JsonNode pending = ok(c.post("/v/organization/exams/attempts", me, Map.of("paperId", paperId, "answers", answers), "交卷")).data();
        assertEquals(1, pending.get("status").asInt(), "有简答题：待阅卷");
        assertEquals(60, pending.get("objectiveScore").asInt());
        long attemptId = pending.get("id").asLong();
        assertNotEquals(200, neg.post("/v/organization/exams/attempts", me, Map.of("paperId", paperId, "answers", answers), "阅卷前再交").code());

        // ---- 阅卷：出题人阅不了，阅卷人出分 ----
        JsonNode queue = ok(c.get("/a/organization/exam-attempts?status=1&paperId=" + paperId + "&page=1&size=100", grader, "阅卷队列")).data();
        assertTrue(queue.get("records").toString().contains("\"id\":\"" + attemptId + "\"") || queue.get("records").toString().contains("\"id\":" + attemptId),
                queue.toString());
        long textQuestion = questions.get(3).get("id").asLong();
        Map<String, Object> gradeBody = Map.of("scores", List.of(Map.of("questionId", textQuestion, "score", 5)), "note", "太简略");
        assertNotEquals(200, neg.post("/a/organization/exam-attempts/" + attemptId + "/grade", setter, gradeBody, "出题人阅卷").code());
        JsonNode graded = ok(c.post("/a/organization/exam-attempts/" + attemptId + "/grade", grader, gradeBody, "阅卷人出分")).data();
        assertEquals(65, graded.get("totalScore").asInt());
        assertTrue(graded.get("passed").asBoolean());

        // ---- 成为临时负责人 ----
        JsonNode after = ok(c.get("/v/organization/exams/current", me, "出分后")).data();
        assertTrue(after.get("tempLeader").asBoolean());
        assertEquals("活动临时负责人", ok(c.get("/v/user/profile", me, "我的资料")).data().get("duty").asText());
        JsonNode mine = ok(c.get("/v/organization/exams/attempts/" + attemptId, me, "我的答卷")).data();
        assertFalse(mine.toString().contains("answerDisplay"), "志愿者看不到标准答案：" + mine);

        // ---- 名单、导出、撤销 ----
        assertNotEquals(200, neg.get("/a/organization/temp-leaders", grader, "阅卷人看名单").code());
        long volunteerId = graded.get("volunteerId").asLong();
        JsonNode row = null;
        for (JsonNode r : ok(c.get("/a/organization/temp-leaders?status=1&page=1&size=100", keeper, "名单")).data().get("records")) {
            if (r.get("volunteerId").asLong() == volunteerId) {
                row = r;
            }
        }
        assertTrue(row != null && row.get("paperTitle").asText().contains(tag), String.valueOf(row));
        ok(c.get("/a/organization/exam-attempts?volunteerId=" + volunteerId, keeper, "名单管理员看历史考试"));
        HttpResponse<byte[]> xlsx = raw("/a/organization/temp-leaders/export?status=1", keeper);
        assertEquals(200, xlsx.statusCode());
        assertTrue(xlsx.headers().firstValue("Content-Type").orElse("").contains("spreadsheetml"));
        assertTrue(xlsx.body().length > 1000);
        ok(c.post("/a/organization/temp-leaders/" + row.get("id").asLong() + "/revoke", keeper, Map.of("reason", "活动评价过低"), "撤销"));
        assertEquals("志愿者", ok(c.get("/v/user/profile", me, "撤销后的资料")).data().get("duty").asText());

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
        assertNotEquals(200, neg.get("/a/organization/exam-papers", me, "志愿者 token 调后台").code());
        ok(c.post("/a/organization/exam-papers/" + paperId + "/close", setter, null, "收尾停止"));
    }

    private String account(ApiStressClient c, String root, String username, long permId) {
        long accountId = ok(c.post("/a/organization/sub-accounts", root, Map.of("username", username,
                "password", "pass1234", "realName", "组织部" + username, "department", "组织部"), "建子账号")).dataAsLong();
        ok(c.put("/a/organization/sub-accounts/" + accountId + "/permissions", root, Map.of("permissionIds", List.of(permId)), "授权"));
        return c.adminLogin(username, "pass1234");
    }

    private HttpResponse<byte[]> raw(String path, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", token)
                .GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
