package com.hengde.api.stress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import com.hengde.system.service.OperationLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统治理的 <b>HTTP 全流程</b>（V4 系统治理批）——<b>必跑</b>。
 *
 * <p>真实 multipart 传文件进网盘 → 公开到小程序并设开放窗口 → 志愿者看得到 / 关掉下载就没有地址 →
 * 分享链接免登录打开 → 水印与菜单排序读写 → <b>拦截器真的把这些写操作记进了日志</b>，
 * 而日志只有持 {@code system:log} 的账号看得到。</p>
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
class SystemGovernanceApiFlowTest {

    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34};

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;
    @Autowired
    private OperationLogService operationLogService;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Test
    void vaultPublishShare_watermarkMenus_andEverythingIsLogged() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        // ---- 网盘：建文件夹 → 真实上传 → 登记 ----
        long folder = ok(c.post("/a/system/vault/folders", root, Map.of("name", "协会文件-" + tag), "建文件夹")).dataAsLong();
        HttpResponse<String> up = upload("/a/files/upload?dir=vault", root, "手册.pdf", PDF);
        assertEquals(200, up.statusCode(), up.body());
        String fileUrl = new ObjectMapper().readTree(up.body()).get("data").get("url").asText();
        long fileId = ok(c.post("/a/system/vault/files", root, Map.of("folderId", folder,
                "name", "志愿者手册-" + tag + ".pdf", "fileUrl", fileUrl, "fileSize", 8), "登记文件")).dataAsLong();
        JsonNode files = ok(c.get("/a/system/vault/folders/" + folder + "/files?page=1&size=10", root, "文件列表"))
                .data().get("records");
        assertEquals(10, files.get(0).get("serialNo").asText().length(), "十位编号（Row 75）");
        assertNotEquals(200, neg.post("/a/system/vault/files", root, Map.of("folderId", folder,
                "name", "外链.pdf", "fileUrl", "https://evil.example.com/x.pdf"), "外链文件").code());

        // ---- 公开到小程序 + 开放窗口；志愿者端看得到 ----
        String vol = c.devLogin("sysfile-" + tag);
        assertTrue(ok(c.get("/v/system/files", vol, "志愿者看内置文件")).data().toString().isEmpty()
                        || !ok(c.get("/v/system/files", vol, "志愿者看内置文件")).data().toString().contains("志愿者手册-" + tag),
                "没公开之前不该出现");
        ok(c.put("/a/system/vault/files/" + fileId + "/publish", root,
                Map.of("published", true, "allowDownload", false), "公开但不给下载"));
        JsonNode open = ok(c.get("/v/system/files", vol, "志愿者看内置文件")).data();
        JsonNode mine = null;
        for (JsonNode f : open) {
            if (f.get("name").asText().contains(tag)) {
                mine = f;
            }
        }
        assertTrue(mine != null, "公开之后要出现：" + open);
        assertFalse(mine.has("fileUrl") && !mine.get("fileUrl").isNull(), "关掉下载就不下发地址：" + mine);
        ok(c.put("/a/system/vault/files/" + fileId + "/publish", root,
                Map.of("published", true, "allowDownload", true), "开放下载"));
        for (JsonNode f : ok(c.get("/v/system/files", vol, "再看一次")).data()) {
            if (f.get("name").asText().contains(tag)) {
                assertTrue(f.get("fileUrl").asText().length() > 0, "开放下载之后才给地址");
            }
        }

        // ---- 分享：免登录也能打开，撤销之后打不开 ----
        JsonNode share = ok(c.post("/a/system/vault/files/" + fileId + "/shares", root,
                Map.of("requireLogin", false, "hours", 2), "分享")).data();
        String token = share.get("token").asText();
        assertEquals("志愿者手册-" + tag + ".pdf",
                ok(c.get("/share/files/" + token, null, "免登录打开分享")).data().get("name").asText());
        ok(c.delete("/a/system/vault/shares/" + share.get("id").asLong(), root, "撤销分享"));
        assertNotEquals(200, neg.get("/share/files/" + token, null, "撤销之后").code());

        // ---- 水印与菜单排序 ----
        JsonNode watermark = ok(c.get("/a/system/watermark", root, "水印")).data();
        assertTrue(watermark.get("enabled").asBoolean());
        ok(c.put("/a/system/watermark", root, Map.of("opacityPercent", 15), "改水印"));
        assertEquals(15, ok(c.get("/a/system/watermark", root, "再读水印")).data().get("opacityPercent").asInt());
        ok(c.put("/a/system/menu-order", root, Map.of("items", List.of(
                Map.of("key", "overview", "name", "概览", "sort", 10, "visible", true),
                Map.of("key", "vault-" + tag, "name", "文件网盘", "sort", 20, "visible", true))), "改菜单排序"));
        assertEquals("overview", ok(c.get("/a/system/menu-order", root, "读菜单排序")).data()
                .get("items").get(0).get("key").asText());

        // ---- 页面访问上报 ----
        ok(c.post("/a/system/page-views", root, Map.of("page", "文件网盘-" + tag, "path", "/vault"), "上报页面访问"));

        // ---- 日志：上面这些写操作真的被拦截器记下来了；没权限的看不到 ----
        while (operationLogService.flush() > 0) {
            // 落库是异步的，用例里把队列写完再查
        }
        JsonNode logs = ok(c.get("/a/system/logs?page=1&size=100&keyword=" + java.net.URLEncoder.encode("文件", StandardCharsets.UTF_8),
                root, "查日志")).data().get("records");
        assertTrue(logs.size() > 0, "写操作应当有日志");
        // ⚠️ 断言要落在**拦截器记的那一条**上：页面访问是控制器自己记的，拿它作证据的话，
        // 把拦截器整个关掉用例照样绿（变异验证当场撞出来的）
        boolean interceptorLogged = false;
        for (JsonNode log : logs) {
            if ("POST".equals(log.path("method").asText()) && log.path("uri").asText().endsWith("/a/system/vault/folders")
                    && log.path("logTypeLabel").asText().equals("操作")) {
                interceptorLogged = true;
                assertTrue(log.path("action").asText().contains("文件夹"), "动作名取的是 Swagger summary：" + log);
                assertTrue(log.path("actorName").asText().length() > 0, "记得下操作人是谁");
            }
        }
        assertTrue(interceptorLogged, "拦截器要把 /a/** 的写操作记下来：" 
                + logs.toString().substring(0, Math.min(500, logs.toString().length())));
        assertTrue(logs.toString().contains("文件网盘-" + tag), "前端上报的页面访问也在");

        long viewerId = ok(c.post("/a/organization/sub-accounts", root, Map.of("username", "lg" + tag, "password", "pass1234",
                "realName", "看日志的人", "department", "监察部"), "建子账号")).dataAsLong();
        String viewer = c.adminLogin("lg" + tag, "pass1234");
        assertNotEquals(200, neg.get("/a/system/logs?page=1&size=5", viewer, "没有 system:log").code());
        ok(c.put("/a/organization/sub-accounts/" + viewerId + "/permissions", root,
                Map.of("permissionIds", List.of(permissionId(c, root, "system:log"))), "授日志查看权"));
        String viewerAgain = c.adminLogin("lg" + tag, "pass1234");
        assertTrue(ok(c.get("/a/system/logs?page=1&size=5", viewerAgain, "授权之后")).data().get("records").size() > 0);
        assertNotEquals(200, neg.get("/a/system/vault/folders", viewerAgain, "日志权不等于网盘权").code());
        assertNotEquals(200, neg.get("/a/system/logs?page=1&size=5", vol, "志愿者 token 调后台").code());

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    /**
     * 到家详细地址的字段级可见性（Row 63）：没有 {@code activity:home-address} 的账号拿到的响应里<b>根本没有地址</b>。
     *
     * <p>这一条只有走 HTTP 才验得到——判定在控制器（{@code hasPermission}），服务层只收一个布尔。
     * 考勤行用 JDBC 直接造：这条用例要验的是可见性，不是签到流程。</p>
     */
    @Test
    void homeAddressIsOnlyVisibleWithThePermission() {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        String vol = c.devLogin("home-" + tag);
        long volunteerId = ok(c.get("/v/user/profile", vol, "资料")).data().get("no").asLong();

        LocalDateTime start = LocalDateTime.now().minusDays(1).withNano(0);
        long activityId = ok(c.post("/a/activity/activities", root, Map.of(
                "title", "到家用例-" + tag,
                "startTime", start.format(TS), "endTime", start.plusHours(2).format(TS),
                "slots", List.of(Map.of("projectName", "上午场", "startTime", start.format(TS),
                        "endTime", start.plusHours(2).format(TS), "needCount", 5))), "建活动")).dataAsLong();
        Long slotId = jdbc.queryForObject("SELECT id FROM activity_slot WHERE activity_id = ? ORDER BY id LIMIT 1",
                Long.class, activityId);
        jdbc.update("INSERT INTO activity_attendance (activity_id, slot_id, volunteer_id, check_in_time, check_in_method, "
                        + "attend_status, secretary_status, points_status, confirm_home_time, confirm_home_lat, confirm_home_lng, "
                        + "confirm_home_address, create_time, update_time, is_deleted) "
                        + "VALUES (?, ?, ?, ?, 1, 1, 0, 0, ?, 21.2, 110.1, ?, NOW(), NOW(), 0)",
                activityId, slotId, volunteerId, start.plusHours(1), start.plusHours(3), "雷州市某某小区 3 栋 201-" + tag);

        // 超管（通配）看得到地址
        JsonNode asRoot = ok(c.get("/a/activity/activities/" + activityId + "/home-confirmations", root, "超管看到家名单")).data();
        assertEquals(1, asRoot.size());
        assertTrue(asRoot.get(0).get("confirmed").asBoolean());
        assertTrue(asRoot.toString().contains("雷州市某某小区 3 栋 201-" + tag), "超管看得到详细地址");

        // 只有 activity:manage 的账号：看得到「已到家」，看不到地址与坐标
        long plainId = ok(c.post("/a/organization/sub-accounts", root, Map.of("username", "hm" + tag, "password", "pass1234",
                "realName", "现场管理员", "department", "组织部"), "建子账号")).dataAsLong();
        ok(c.put("/a/organization/sub-accounts/" + plainId + "/permissions", root,
                Map.of("permissionIds", List.of(permissionId(c, root, "activity:manage"))), "只授现场管理"));
        String plain = c.adminLogin("hm" + tag, "pass1234");
        JsonNode asPlain = ok(c.get("/a/activity/activities/" + activityId + "/home-confirmations", plain, "现场管理员看名单")).data();
        assertEquals(1, asPlain.size());
        assertTrue(asPlain.get(0).get("confirmed").asBoolean(), "「已到家」照常显示");
        assertFalse(asPlain.toString().contains("雷州市某某小区"), "地址不下发：" + asPlain);
        assertFalse(asPlain.toString().contains("110.1"), "坐标也不下发：" + asPlain);

        // 加上 activity:home-address 之后才给
        ok(c.put("/a/organization/sub-accounts/" + plainId + "/permissions", root,
                Map.of("permissionIds", List.of(permissionId(c, root, "activity:manage"),
                        permissionId(c, root, "activity:home-address"))), "再授到家地址权"));
        String withAddr = c.adminLogin("hm" + tag, "pass1234");
        assertTrue(ok(c.get("/a/activity/activities/" + activityId + "/home-confirmations", withAddr, "授权之后"))
                .data().toString().contains("雷州市某某小区 3 栋 201-" + tag));
        assertNotEquals(200, neg.get("/a/activity/activities/" + activityId + "/home-confirmations", vol, "志愿者 token").code());

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    private static long permissionId(ApiStressClient c, String root, String code) {
        for (JsonNode p : ok(c.get("/a/organization/permissions", root, "权限点列表")).data()) {
            if (code.equals(p.get("code").asText())) {
                return p.get("id").asLong();
            }
        }
        throw new AssertionError("没有权限点 " + code);
    }

    private HttpResponse<String> upload(String path, String token, String filename, byte[] content) throws Exception {
        String boundary = "----systest" + System.nanoTime();
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

    private static ApiStressClient.Resp ok(ApiStressClient.Resp r) {
        ApiStressClient.requireOk(r, "HTTP 调用");
        return r;
    }
}
