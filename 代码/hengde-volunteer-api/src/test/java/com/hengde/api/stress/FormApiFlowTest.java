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

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 问卷引擎的 <b>HTTP 全流程</b>（V4 问卷引擎批）——<b>必跑</b>。
 *
 * <p>服务层用例证明不了三件只在 HTTP 这一层才成立的事：① 答案的 {@code value} 是 {@code Object}，
 * 经 Jackson 反序列化后分别是字符串 / 数组 / 布尔，校验器认的正是这几种形状；
 * ② 文件题的 URL 由 {@code POST /v/files/form-file} 真实上传产出，提交时的「是不是本系统传的」判定要与它对得上；
 * ③ 导出是真的 xlsx 下载（运行期表头走 EasyExcel 的动态表头）。</p>
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
class FormApiFlowTest {

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;

    @Test
    void formLifecycle_submitWithRealUpload_exportAndManagerApplication() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        // ---- 管理端建通用问卷并发布 ----
        long formId = ok(c.post("/a/organization/forms", admin, Map.of(
                "title", "接口问卷-" + tag,
                "questions", List.of(
                        Map.of("type", 1, "title", "来自哪里", "options", List.of("雷城", "客路")),
                        Map.of("type", 2, "title", "擅长", "options", List.of("摄影", "写作", "急救"), "maxSelect", 2),
                        Map.of("type", 3, "title", "参加过活动"),
                        Map.of("type", 4, "title", "学校", "maxLength", 30),
                        Map.of("type", 6, "title", "简历", "required", false, "maxFiles", 1),
                        Map.of("type", 7, "title", "开始日期", "minDate", "2026-01-01"))), "建问卷")).dataAsLong();
        JsonNode detail = ok(c.get("/a/organization/forms/" + formId, admin, "问卷详情")).data();
        JsonNode qs = detail.get("questions");
        assertEquals(6, qs.size());
        assertEquals("B", qs.get(0).get("options").get(1).get("key").asText());
        ok(c.post("/a/organization/forms/" + formId + "/publish", admin, null, "发布"));

        // ---- 志愿者：列表、详情、真实上传一个附件、提交 ----
        String me = c.devLogin("form-" + tag);
        JsonNode list = ok(c.get("/v/organization/forms?page=1&size=50", me, "可填问卷")).data().get("records");
        assertTrue(contains(list, formId), "发布后出现在可填列表：" + list);
        JsonNode vDetail = ok(c.get("/v/organization/forms/" + formId, me, "志愿者看详情")).data();
        assertEquals(false, vDetail.get("submitted").asBoolean());

        HttpResponse<String> uploaded = upload("/v/files/form-file", me, "cv.pdf",
                "%PDF-1.4\n% form api test\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        String fileUrl = new com.fasterxml.jackson.databind.ObjectMapper().readTree(uploaded.body())
                .get("data").get("url").asText();
        assertTrue(fileUrl.contains("/form/"), fileUrl);

        ApiStressClient.Resp outside = neg.post("/v/organization/forms/" + formId + "/submissions", me, Map.of(
                "answers", answers(qs, "https://evil.example.com/cv.pdf")), "外链当附件");
        assertNotEquals(200, outside.code());
        assertTrue(outside.message().contains("文件无效"), outside.message());

        long submissionId = ok(c.post("/v/organization/forms/" + formId + "/submissions", me, Map.of(
                "answers", answers(qs, fileUrl)), "提交答卷")).dataAsLong();
        assertTrue(ok(c.get("/v/organization/forms/" + formId, me, "再看详情")).data().get("submitted").asBoolean());
        assertNotEquals(200, neg.post("/v/organization/forms/" + formId + "/submissions", me, Map.of(
                "answers", answers(qs, fileUrl)), "再交一次").code());

        // ---- 管理端看答卷、导出 ----
        JsonNode subs = ok(c.get("/a/organization/forms/" + formId + "/submissions?page=1&size=10", admin, "答卷列表"))
                .data().get("records");
        assertEquals(1, subs.size());
        JsonNode sub = ok(c.get("/a/organization/forms/submissions/" + submissionId, admin, "答卷详情")).data();
        assertEquals("写作、急救", sub.get("answers").get(1).get("display").asText());
        assertEquals("是", sub.get("answers").get(2).get("display").asText());
        assertEquals(fileUrl, sub.get("answers").get(4).get("display").asText());

        HttpResponse<byte[]> xlsx = raw("/a/organization/forms/" + formId + "/submissions/export", admin);
        assertEquals(200, xlsx.statusCode());
        assertTrue(xlsx.headers().firstValue("Content-Type").orElse("").contains("spreadsheetml"), "导出是 xlsx 下载");
        assertTrue(xlsx.body().length > 1000);

        // ---- 报名管理团队：挂问卷、带答卷申请、后台详情与批量下载 ----
        JsonNode existing = ok(c.get("/a/organization/forms?scene=2&status=1&page=1&size=50", admin, "收集中的报名问卷"))
                .data().get("records");
        for (JsonNode f : existing) {
            ok(c.post("/a/organization/forms/" + f.get("id").asText() + "/close", admin, null, "停掉旧的报名问卷"));
        }
        long managerForm = ok(c.post("/a/organization/forms", admin, Map.of("scene", 2, "title", "报名问卷-" + tag,
                "questions", List.of(Map.of("type", 1, "title", "期望部门", "options", List.of("组织部", "宣传部")))),
                "建报名问卷")).dataAsLong();
        ok(c.post("/a/organization/forms/" + managerForm + "/publish", admin, null, "发布报名问卷"));
        JsonNode current = ok(c.get("/v/organization/forms/scenes/2/current", me, "当前报名问卷")).data();
        String deptQuestion = current.get("questions").get(0).get("id").asText();
        long appId = ok(c.post("/v/organization/manager-applications", me, Map.of("reason", "想出力",
                "answers", List.of(Map.of("questionId", deptQuestion, "value", "B"))), "申请管理团队")).dataAsLong();
        JsonNode app = ok(c.get("/a/organization/manager-applications/" + appId, admin, "申请详情")).data();
        assertEquals("宣传部", app.get("formAnswers").get(0).get("display").asText());
        HttpResponse<byte[]> apps = raw("/a/organization/manager-applications/export?status=0", admin);
        assertEquals(200, apps.statusCode());
        assertTrue(apps.headers().firstValue("Content-Type").orElse("").contains("spreadsheetml"));
        ok(c.post("/a/organization/forms/" + managerForm + "/close", admin, null, "停掉报名问卷"));

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());

        // ---- 负向：志愿者 token 调后台、报名问卷不能直接交 ----
        assertNotEquals(200, neg.get("/a/organization/forms", me, "志愿者调后台").code());
        assertNotEquals(200, neg.post("/v/organization/forms/" + managerForm + "/submissions", me,
                Map.of("answers", List.of()), "直接交报名问卷").code());
    }

    private static List<Map<String, Object>> answers(JsonNode qs, String fileUrl) {
        return List.of(
                Map.of("questionId", qs.get(0).get("id").asText(), "value", "A"),
                Map.of("questionId", qs.get(1).get("id").asText(), "value", List.of("C", "B")),
                Map.of("questionId", qs.get(2).get("id").asText(), "value", true),
                Map.of("questionId", qs.get(3).get("id").asText(), "value", "雷州一中"),
                Map.of("questionId", qs.get(4).get("id").asText(), "value", List.of(fileUrl)),
                Map.of("questionId", qs.get(5).get("id").asText(), "value", "2026-10-01"));
    }

    private static boolean contains(JsonNode records, long id) {
        for (JsonNode r : records) {
            if (r.get("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }

    private HttpResponse<String> upload(String path, String token, String filename, byte[] content) throws Exception {
        String boundary = "----formapitest" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
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
