package com.hengde.api.stress;

import com.fasterxml.jackson.databind.JsonNode;
import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台控制台接 V3 / V4 页面时补的后端缺口（控制台 V3 / V4 批次）——<b>必跑</b>。
 *
 * <p>控制台是「只能打 /a/** 」的：管理端 token 过不了 /v/**。凡是某个动作在后台有入口、
 * 却只有志愿者端能把要处理的东西列出来的，控制台上就够不着——这里的每一条都是这样撞出来的。</p>
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
class AdminConsoleGapsApiTest {

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 下架评价 DELETE /a/donate/reviews/{id} 早就有，可评价只能在志愿者端列出来——
     * 后台看不到评价，也就无从下架。补 GET /a/donate/goods/{id}/reviews。
     */
    @Test
    void adminCanListGoodsReviews_thenDelistOne() {
        ApiStressClient c = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        long goodsId = ok(c.post("/a/donate/goods", admin, Map.of("name", "评价用例-" + tag,
                "specs", List.of(Map.of("name", "标准", "points", 10, "stock", 5))), "建商品")).dataAsLong();
        ok(c.post("/a/donate/goods/" + goodsId + "/submit", admin, null, "提交"));
        ok(c.post("/a/donate/goods/" + goodsId + "/approve", admin, null, "通过"));
        String specId = ok(c.get("/a/donate/goods/" + goodsId, admin, "详情")).data().get("specs").get(0).get("id").asText();

        String key = "gap-rv-" + tag;
        String donor = c.devLogin(key);
        long vid = jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:" + key);
        ok(c.post("/a/activity/points/adjust", admin, Map.of("volunteerId", vid, "changeAmount", 50,
                "reason", "评价用例预置", "requestId", "gap-rv-" + tag), "预置积分"));
        String orderId = ok(c.post("/v/donate/orders", donor, Map.of("specId", Long.parseLong(specId)), "下单")).data().get("id").asText();
        ok(c.post("/a/donate/orders/" + orderId + "/approve", admin, null, "审核通过"));
        String code = ok(c.get("/v/donate/orders/" + orderId, donor, "取货码")).data().get("pickupCode").asText();
        ok(c.post("/a/donate/orders/verify", admin, Map.of("code", code), "核销"));
        ok(c.post("/v/donate/orders/" + orderId + "/reviews", donor, Map.of("rating", 2, "content", "不当内容"), "评价"));

        JsonNode page = ok(c.get("/a/donate/goods/" + goodsId + "/reviews?page=1&size=10", admin, "后台列评价")).data();
        assertEquals(1, page.get("total").asInt(), page.toString());
        JsonNode review = page.get("records").get(0);
        assertEquals("不当内容", review.get("content").asText());
        assertTrue(review.hasNonNull("volunteerName"), "后台要看得到是谁评的：" + review);

        ok(c.delete("/a/donate/reviews/" + review.get("id").asText(), admin, "下架"));
        assertEquals(0, ok(c.get("/a/donate/goods/" + goodsId + "/reviews?page=1&size=10", admin, "下架后")).data()
                .get("total").asInt(), "下架的评价不再列出");

        // 未登录打这个新端点：401，不是 200
        assertEquals(401, new ApiStressClient(port).get("/a/donate/goods/" + goodsId + "/reviews", null, "未登录").http());
    }

    /**
     * 企业赞助的商品，赞助方名称是企业名称的快照。后台在「商品管理」里编辑时表单里带着赞助方名称一栏，
     * 此前 update 会照单全收——改了就与企业脱钩、清空就没了名字。现在只有平台自营的商品才按表单写；
     * 出参补 sponsorEnterpriseId，控制台据此把这一栏锁住。
     */
    @Test
    void editingSponsoredGoodsKeepsTheEnterpriseNameSnapshot() {
        ApiStressClient c = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        Map<String, Object> ent = new java.util.HashMap<>();
        ent.put("name", "赞助快照企业-" + tag);
        ent.put("creditCode", cn.hutool.core.util.CreditCodeUtil.randomCreditCode());
        ent.put("leaderName", "张三");
        ent.put("leaderPhone", "13900000000");
        ent.put("username", "sp_" + tag);
        ent.put("password", "abc12345");
        long enterpriseId = ok(c.post("/a/enterprise/enterprises", admin, ent, "代建企业")).dataAsLong();

        List<Map<String, Object>> specs = List.of(Map.of("name", "标准", "points", 10, "stock", 5));
        long sponsored = ok(c.post("/a/enterprise/enterprises/" + enterpriseId + "/goods", admin,
                Map.of("name", "赞助商品-" + tag, "specs", specs), "代发布")).dataAsLong();
        long own = ok(c.post("/a/donate/goods", admin,
                Map.of("name", "自营商品-" + tag, "sponsorName", "原赞助方", "specs", specs), "自营")).dataAsLong();

        JsonNode before = ok(c.get("/a/donate/goods/" + sponsored, admin, "详情")).data();
        assertEquals(enterpriseId, before.get("sponsorEnterpriseId").asLong(), "后台要知道这是哪家企业赞助的：" + before);
        assertEquals("赞助快照企业-" + tag, before.get("sponsorName").asText());

        Map<String, Object> edit = new java.util.HashMap<>(Map.of("name", "改名-" + tag, "sponsorName", "冒名企业"));
        ok(c.put("/a/donate/goods/" + sponsored, admin, edit, "改企业商品"));
        JsonNode after = ok(c.get("/a/donate/goods/" + sponsored, admin, "改后")).data();
        assertEquals("改名-" + tag, after.get("name").asText(), "其它字段照改");
        assertEquals("赞助快照企业-" + tag, after.get("sponsorName").asText(), "企业赞助的，名称不随表单改");

        ok(c.put("/a/donate/goods/" + own, admin, edit, "改自营商品"));
        JsonNode ownAfter = ok(c.get("/a/donate/goods/" + own, admin, "自营改后")).data();
        assertEquals("冒名企业", ownAfter.get("sponsorName").asText(), "平台自营的赞助方名称照常按表单写");
        assertTrue(ownAfter.path("sponsorEnterpriseId").isMissingNode() || ownAfter.get("sponsorEnterpriseId").isNull());
    }

    /**
     * 活动总结能写不能读：{@code POST /a/activity/activities/{id}/summary} 早就有，可后台详情出参里没有总结，
     * 控制台上传过一次之后再打开，看到的永远是空的。详情补 runStatus / summaryText / summaryImages / summaryTime。
     */
    @Test
    void adminDetailShowsRunStatusAndTheSummaryThatWasUploaded() {
        ApiStressClient c = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        java.time.LocalDateTime start = java.time.LocalDateTime.now().plusDays(1).withNano(0);
        long aid = ok(c.post("/a/activity/activities", admin, Map.of(
                "title", "总结用例-" + tag, "location", "雷州市西湖公园",
                "startTime", fmt.format(start), "endTime", fmt.format(start.plusHours(2)),
                "slots", List.of(Map.of("projectName", "上午场", "startTime", fmt.format(start),
                        "endTime", fmt.format(start.plusHours(2)), "needCount", 5))), "发布活动")).dataAsLong();
        assertEquals(0, ok(c.get("/a/activity/activities/" + aid, admin, "详情")).data().get("runStatus").asInt());

        ok(c.post("/a/activity/activities/" + aid + "/start", admin, null, "开始"));
        ok(c.post("/a/activity/activities/" + aid + "/finish", admin, null, "结束"));
        ok(c.post("/a/activity/activities/" + aid + "/summary", admin,
                Map.of("summaryText", "到场 5 人，清理垃圾 12 袋", "summaryImages", "https://example.com/a.jpg"), "上传总结"));

        JsonNode d = ok(c.get("/a/activity/activities/" + aid, admin, "结束后详情")).data();
        assertEquals(2, d.get("runStatus").asInt(), d.toString());
        assertEquals("到场 5 人，清理垃圾 12 袋", d.path("summaryText").asText(), "上传的总结要读得回来：" + d);
        assertEquals("https://example.com/a.jpg", d.path("summaryImages").asText());
        assertTrue(d.hasNonNull("summaryTime"));
    }

    /**
     * 后台现场考勤名单要从<b>报名</b>来，不能从考勤行来：「缺席」恰恰是给没来签到的人标的，
     * 只列考勤行（服务记录）的话这个人根本不在列表里。补 GET /a/activity/activities/{id}/attendance-roster。
     */
    @Test
    void attendanceRosterListsNoShowsSoTheyCanBeMarkedAbsent() {
        ApiStressClient c = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        java.time.LocalDateTime start = java.time.LocalDateTime.now().plusDays(1).withNano(0);
        long aid = ok(c.post("/a/activity/activities", admin, Map.of(
                "title", "考勤名单用例-" + tag, "location", "雷州市西湖公园", "needAudit", 0,
                "startTime", fmt.format(start), "endTime", fmt.format(start.plusHours(2)),
                "slots", List.of(Map.of("projectName", "上午场", "startTime", fmt.format(start),
                        "endTime", fmt.format(start.plusHours(2)), "needCount", 5))), "发布活动")).dataAsLong();
        long slotId = ok(c.get("/a/activity/activities/" + aid, admin, "详情")).data().get("slots").get(0).get("id").asLong();
        String key = "roster-" + tag;
        String vol = c.devLogin(key);
        long vid = jdbc.queryForObject("SELECT id FROM volunteer WHERE openid = ?", Long.class, "dev:" + key);
        ok(c.post("/v/activity/activities/" + aid + "/enroll", vol, Map.of("slotIds", List.of(slotId)), "报名"));

        JsonNode roster = ok(c.get("/a/activity/activities/" + aid + "/attendance-roster", admin, "考勤名单")).data();
        assertEquals(1, roster.size(), "没签到的人也要在名单里：" + roster);
        assertEquals(vid, roster.get(0).get("volunteerId").asLong());
        assertTrue(roster.get(0).path("checkInTime").isNull() || roster.get(0).path("checkInTime").isMissingNode());
        assertEquals(0, ok(c.get("/a/activity/service-records?activityId=" + aid, admin, "服务记录")).data().get("total").asInt(),
                "服务记录只列考勤行——这正是不能拿它当现场名单的原因");

        ok(c.call("PATCH", "/a/activity/activities/" + aid + "/attendances/" + vid, admin,
                Map.of("slotId", slotId, "attendStatus", 4), "标缺席"));
        JsonNode after = ok(c.get("/a/activity/activities/" + aid + "/attendance-roster", admin, "标完再看")).data().get(0);
        assertEquals(4, after.get("attendStatus").asInt(), after.toString());
        assertEquals(0, after.get("serviceMinutes").asInt(), "缺席时长记 0");
        assertEquals(1, after.get("violationCount").asInt(), "缺席自动生成一条违规");

        assertEquals(401, new ApiStressClient(port).get("/a/activity/activities/" + aid + "/attendance-roster", null, "未登录").http());
    }

    /**
     * 捐书活动封面、结对 / 众筹项目封面与来信配图此前没有上传目录：只有 donate:item 或 donate:project 的账号
     * 在后台传不了任何图（通用上传按 dir 核权限，没有一个 dir 认这两个权限点）。补 dir=book / dir=project，各认各的。
     */
    @Test
    void bookAndProjectUploadDirsFollowTheirOwnPermissions() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        long accId = ok(c.post("/a/organization/sub-accounts", root, Map.of("username", "bk" + tag, "password", "pass1234",
                "realName", "捐书仓库", "department", "秘书部"), "建子账号")).dataAsLong();
        ok(c.put("/a/organization/sub-accounts/" + accId + "/permissions", root,
                Map.of("permissionIds", List.of(permissionId(c, root, "donate:item"))), "只授 donate:item"));
        String book = c.adminLogin("bk" + tag, "pass1234");

        java.net.http.HttpResponse<String> ok1 = upload("/a/files/upload", book, "book", "cover.png");
        assertEquals(200, ok1.statusCode(), ok1.body());
        assertTrue(ok1.body().contains("\"url\""), ok1.body());
        java.net.http.HttpResponse<String> no1 = upload("/a/files/upload", book, "project", "cover.png");
        assertEquals(403, no1.statusCode(), "没有 donate:project 不能往 project 目录传：" + no1.body());
        java.net.http.HttpResponse<String> ok2 = upload("/a/files/upload", root, "project", "cover.png");
        assertEquals(200, ok2.statusCode(), ok2.body());
    }

    private static long permissionId(ApiStressClient c, String root, String code) {
        for (JsonNode p : ok(c.get("/a/organization/permissions", root, "权限点列表")).data()) {
            if (code.equals(p.get("code").asText())) {
                return p.get("id").asLong();
            }
        }
        throw new AssertionError("没有权限点 " + code);
    }

    private java.net.http.HttpResponse<String> upload(String path, String token, String dir, String filename) throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        String boundary = "----gaptest" + System.nanoTime();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"dir\"\r\n\r\n" + dir + "\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(png);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api" + path))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 后台改心愿要把上报单位 id 原样带回（修改是全量语义，不带＝清空），可出参里只有单位名称没有 id——
     * 控制台照着详情回填表单再保存一次，上报单位就被清空了。出参补 reportOrgId（仅管理端）。
     */
    @Test
    void editingAWishFromItsOwnDetailKeepsTheReportingOrg() {
        ApiStressClient c = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        long orgId = ok(c.post("/a/donate/recipient-orgs", admin, Map.of("name", "上报小学-" + tag), "建受赠单位")).dataAsLong();
        long wishId = ok(c.post("/a/donate/wishes", admin, Map.of("title", "一个书包-" + tag, "childName", "小明",
                "childAge", 9, "reportOrgId", orgId), "建心愿")).dataAsLong();

        JsonNode w = ok(c.get("/a/donate/wishes/" + wishId, admin, "详情")).data().get("wish");
        assertEquals(orgId, w.path("reportOrgId").asLong(), "后台要拿得到上报单位 id：" + w);

        // 照着详情回填再保存（控制台的编辑就是这么做的），只改标题
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("title", "一个新书包-" + tag);
        body.put("childName", w.get("childName").asText());
        body.put("childAge", w.get("childAge").asInt());
        body.put("reportOrgId", w.path("reportOrgId").isMissingNode() || w.get("reportOrgId").isNull() ? null : w.get("reportOrgId").asLong());
        ok(c.put("/a/donate/wishes/" + wishId, admin, body, "改标题"));
        JsonNode after = ok(c.get("/a/donate/wishes/" + wishId, admin, "改后")).data().get("wish");
        assertEquals("上报小学-" + tag, after.path("reportOrgName").asText(), "只改了标题，上报单位不能丢：" + after);
    }

    /**
     * <b>真的读一次 xlsx</b>。依赖调解选中了 commons-io 2.14.0，而 POI 5.4.0 读 xlsx 要调 2.18 才有的
     * {@code BoundedInputStream.builder()}——所有 Excel 导入都以 NoSuchMethodError 失败，对用户只显示
     * 「Excel 解析失败，请检查文件格式」。此前没有任何用例读过一个 xlsx，所以构建与测试一直是绿的。
     */
    @Test
    void excelImportActuallyReadsAnXlsx() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        ok(c.post("/a/donate/recipient-orgs", admin, Map.of("name", "导入小学-" + tag), "建受赠单位"));

        com.hengde.donate.dto.WishDTOs.ImportRow row = new com.hengde.donate.dto.WishDTOs.ImportRow();
        row.setTitle("导入心愿-" + tag);
        row.setChildName("小导");
        row.setChildAge(9);
        row.setReportOrgName("导入小学-" + tag);
        java.io.ByteArrayOutputStream xlsx = new java.io.ByteArrayOutputStream();
        com.alibaba.excel.EasyExcel.write(xlsx, com.hengde.donate.dto.WishDTOs.ImportRow.class).sheet("心愿").doWrite(List.of(row));

        java.net.http.HttpResponse<String> resp = uploadFile("/a/donate/wishes/import", admin, "wishes.xlsx", xlsx.toByteArray(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertEquals(200, resp.statusCode(), "导入读不了 xlsx：" + resp.body());
        assertTrue(resp.body().contains("\"imported\":1"), resp.body());
        assertEquals(1, ok(c.get("/a/donate/wishes?page=1&size=5&keyword=" + java.net.URLEncoder.encode("导入心愿-" + tag,
                java.nio.charset.StandardCharsets.UTF_8), admin, "查导入的心愿")).data().get("total").asInt());
    }

    private java.net.http.HttpResponse<String> uploadFile(String path, String token, String filename, byte[] content, String mime) throws Exception {
        String boundary = "----gapfile" + System.nanoTime();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api" + path))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return java.net.http.HttpClient.newHttpClient().send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 请求本身不对时的状态码。此前三类都落进兜底 → 500「服务器内部错误」+ ERROR 堆栈，
     * 控制台拼错路径时看到「服务器内部错误」，会让人去查服务端而不是查调用方。
     */
    @Test
    void malformedRequestsAreClientErrorsNot500() {
        ApiStressClient c = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);

        ApiStressClient.Resp missingPath = c.get("/a/activity/points/summary", admin, "不存在的路径");
        assertEquals(404, missingPath.http(), missingPath.message());
        assertEquals(404, missingPath.code());

        ApiStressClient.Resp wrongMethod = c.call("DELETE", "/a/donate/orders/verify", admin, null, "方法不对");
        assertEquals(405, wrongMethod.http(), wrongMethod.message());
        assertEquals(405, wrongMethod.code());

        ApiStressClient.Resp missingParam = c.call("PATCH", "/a/donate/coupons/1/status", admin, null, "缺必填参数");
        assertEquals(400, missingParam.http(), missingParam.message());
        assertTrue(missingParam.message().contains("status"), "要说出缺的是哪个参数：" + missingParam.message());
    }

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "");
        return r;
    }
}
