package com.hengde.api.stress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动相册的 <b>HTTP 全流程</b>（V4 活动相册批）——<b>必跑</b>。
 *
 * <p>后台发活动；志愿者报名、真实 multipart 上传原图、打开活动相册（自动建）、带评论上传并同步到交流平台（刷得到那条帖子）；
 * 没报名的人传不了；后台审核积分、志愿者积分总览里多出来；后台批量下载与删照片；志愿者 token 调后台被拒。</p>
 *
 * <p><b>需本机 Docker</b>（MySQL + Redis）。</p>
 *
 * @author hengde
 */
@SpringBootTest(classes = HengdeVolunteerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hengde.auth.dev-login-enabled=true", "hengde.social.digest-enabled=false"})
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, RedisTestcontainersConfig.class})
class AlbumApiFlowTest {

    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52};
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
    void enrollUploadSyncReviewDownload() throws Exception {
        ApiStressClient c = new ApiStressClient(port);
        ApiStressClient neg = new ApiStressClient(port);
        String admin = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);
        LocalDateTime start = LocalDateTime.now().plusDays(9).withHour(9).withMinute(0).withSecond(0).withNano(0);
        long aid = ok(c.post("/a/activity/activities", admin, Map.of("title", "相册活动-" + tag, "location", "西湖公园",
                "startTime", FMT.format(start), "endTime", FMT.format(start.plusHours(2)),
                "slots", List.of(Map.of("projectName", "上午", "startTime", FMT.format(start), "endTime", FMT.format(start.plusHours(2)), "needCount", 0))),
                "发活动")).dataAsLong();

        String me = c.devLogin("al-" + tag);
        long meId = idOf(c, me);
        verifyPhone(meId);
        long slot = ok(c.get("/v/activity/activities/" + aid, me, "活动详情")).data().get("slots").get(0).get("id").asLong();
        ok(c.post("/v/activity/activities/" + aid + "/enroll", me, Map.of("slotIds", List.of(slot)), "报名"));

        JsonNode album = ok(c.get("/v/activity/activities/" + aid + "/album", me, "打开活动相册")).data();
        long albumId = album.get("id").asLong();
        assertTrue(album.get("title").asText().endsWith("相册活动-" + tag), album.toString());
        assertTrue(album.get("uploadable").asBoolean());

        List<String> urls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            HttpResponse<String> up = upload("/v/files/album-photo", me, "p" + i + ".png");
            assertEquals(200, up.statusCode(), up.body());
            urls.add(new ObjectMapper().readTree(up.body()).get("data").get("url").asText());
        }
        long batch = ok(c.post("/v/activity/albums/" + albumId + "/photos", me, Map.of("photoUrls", urls, "comment", "合影 " + tag), "上传"))
                .dataAsLong();
        boolean synced = false;
        for (JsonNode p : ok(c.get("/v/social/posts?tab=latest&keyword=" + tag + "&page=1&size=10", me, "交流平台")).data().get("records")) {
            synced |= p.get("content").asText().contains("合影 " + tag) && p.get("mediaUrls").size() == 4;
        }
        assertTrue(synced, "同步到了交流平台");
        assertEquals(4, ok(c.get("/v/activity/albums/" + albumId, me, "相册详情")).data().get("photoCount").asInt());

        String outsider = c.devLogin("ao-" + tag);
        assertNotEquals(200, neg.post("/v/activity/albums/" + albumId + "/photos", outsider, Map.of("photoUrls", urls.subList(0, 1)),
                "没报名的人上传").code());
        assertNotEquals(200, neg.get("/a/activity/album-batches", me, "志愿者 token 调后台").code());

        // ---- 后台审核积分（默认每 3 张 1 分）----
        boolean queued = false;
        for (JsonNode b : ok(c.get("/a/activity/album-batches?status=0&page=1&size=100", admin, "积分审核队列")).data().get("records")) {
            queued |= b.get("id").asLong() == batch;
        }
        assertTrue(queued);
        assertEquals(1, ok(c.post("/a/activity/album-batches/" + batch + "/approve", admin, null, "通过")).data().asInt());
        assertEquals(1, ok(c.get("/v/activity/points", me, "我的积分")).data().get("totalEarned").asInt());

        // ---- 批量下载与删照片 ----
        JsonNode files = ok(c.get("/a/activity/albums/" + albumId + "/download", admin, "批量下载")).data();
        assertEquals(4, files.size());
        long photoId = ok(c.get("/a/activity/albums/" + albumId + "/photos?page=1&size=10", admin, "照片")).data().get("records").get(0).get("id").asLong();
        ok(c.delete("/a/activity/album-photos/" + photoId, admin, "删照片"));
        assertEquals(3, ok(c.get("/v/activity/albums/" + albumId, me, "相册详情")).data().get("photoCount").asInt());

        assertEquals(0, c.totalFailed(), "正向流程里不应有任何失败：" + c.failureSamples());
    }

    private void verifyPhone(long id) {
        String phone = "133" + String.format("%08d", Math.floorMod(System.nanoTime() + id, 100_000_000L));
        jdbc.update("UPDATE volunteer SET phone = ?, phone_hash = ? WHERE id = ?", cryptoUtil.encrypt(phone), cryptoUtil.hashPhone(phone), id);
    }

    private static long idOf(ApiStressClient c, String token) {
        return ok(c.get("/v/user/profile", token, "资料")).data().get("no").asLong();
    }

    private HttpResponse<String> upload(String path, String token, String filename) throws Exception {
        String boundary = "----albumtest" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(PNG);
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
