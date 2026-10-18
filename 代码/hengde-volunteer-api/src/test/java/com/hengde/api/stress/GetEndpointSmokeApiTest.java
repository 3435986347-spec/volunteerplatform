package com.hengde.api.stress;

import cn.hutool.core.util.CreditCodeUtil;
import com.hengde.api.HengdeVolunteerApplication;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.testsupport.RedisTestcontainersConfig;
import com.hengde.common.testsupport.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全部 GET 端点在「空数据 / 不存在的 id / 没权限 / 没登录」下都不许报 500——走真实 HTTP，<b>必跑</b>。
 *
 * <p>起因是小程序侧 2026-10 的联调报告：一批接口在线上「GET 就 500」。查下来那是线上跑着 V4 之前的旧包
 * （端点根本不存在，而旧版异常处理把「路径不存在」也报成 500），不是代码缺陷——但报告提的要求是对的：
 * <b>无数据按契约返回空、无权限返回约定的拒绝，不能都落成 500</b>。服务层用例各测各的，证明不了这一条对
 * <b>所有</b>端点成立，这里从 Spring 自己的映射表里把端点枚举出来逐个打，新加的端点自动纳入。</p>
 *
 * <p>身份按前缀分：{@code /v} 用未登录、游客、已实名且验过手机号的志愿者；{@code /a} 用未登录、没有任何权限点的子账号、
 * 超管；{@code /e} 用未登录、正常状态的企业。路径变量一律填一个不存在的 id。400 / 401 / 403 / 404 都算合格——
 * 这里只钉「不是服务器错误」，各端点的业务语义由各自的用例负责。</p>
 *
 * <p>另有三条「部署上去的是不是新包」的探针（{@link #deployProbes_tellANewBuildFromAnOldOne}）：
 * 小程序侧照着它们就能在不登录的情况下判断线上版本，不必等后端看日志。</p>
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
class GetEndpointSmokeApiTest {

    /** 小程序 2026-10-07 报告里在线上报 500 的接口——枚举结果里必须有它们，否则说明枚举本身出了问题。 */
    private static final List<String> REPORTED = List.of(
            "/v/data/complaints/mine",
            "/v/social/posts",
            "/v/organization/exams/current",
            "/v/organization/exams/attempts",
            "/v/organization/forms",
            "/v/activity/albums",
            "/v/activity/rosters",
            "/v/system/files",
            "/v/user/addresses",
            "/v/user/center-contents/{key}",
            "/v/user/notify-preferences");

    private static final String MISSING_ID = "999999999";

    @Value("${local.server.port}")
    private int port;
    @Value("${hengde.auth.super-admin-username:admin}")
    private String adminUser;
    @Value("${hengde.auth.super-admin-password:admin123}")
    private String adminPass;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CryptoUtil cryptoUtil;

    @Test
    void noGetEndpoint_answersWithAServerError() {
        ApiStressClient c = new ApiStressClient(port);
        String root = c.adminLogin(adminUser, adminPass);
        String tag = Long.toString(System.nanoTime(), 36);

        Map<String, Map<String, String>> personas = new LinkedHashMap<>();
        personas.put("/v/", personas("未登录", null, "游客", guest(c, "smoke-guest-" + tag),
                "已实名", verifiedVolunteer(c, "smoke-vol-" + tag)));
        personas.put("/a/", personas("未登录", null, "无权限子账号", noPermissionAdmin(c, root, "sm" + tag),
                "超管", root));
        personas.put("/e/", personas("未登录", null, "企业", enterprise(c, root, tag)));

        TreeSet<String> swept = new TreeSet<>();
        List<String> serverErrors = new ArrayList<>();
        int calls = 0;
        for (String pattern : getPatterns()) {
            String prefix = pattern.substring(0, 3);
            Map<String, String> who = personas.get(prefix);
            if (who == null) {
                continue;
            }
            swept.add(pattern);
            String url = pattern.replaceAll("\\{[^}]+}", MISSING_ID) + "?page=1&size=10";
            for (Map.Entry<String, String> p : who.entrySet()) {
                ApiStressClient.Resp r = c.get(url, p.getValue(), "GET " + pattern);
                calls++;
                if (r.http() >= 500 || r.code() >= 500) {
                    serverErrors.add(p.getKey() + "  GET " + url + "  → HTTP " + r.http() + " / code " + r.code()
                            + "  " + r.message());
                }
            }
        }

        for (String reported : REPORTED) {
            assertTrue(swept.contains(reported), "枚举里没有 " + reported + "——枚举方式坏了，这条用例就什么也没证明");
        }
        assertTrue(serverErrors.isEmpty(), serverErrors.size() + " 次调用报了服务器错误（共 " + calls + " 次）：\n"
                + String.join("\n", serverErrors));
    }

    /**
     * 不登录就能判断线上跑的是不是新包：
     * <ol>
     *   <li>不存在的路径是 404（旧包是 500——2026-09-19 之前路径不存在也落进兜底的 500）；</li>
     *   <li>志愿者证核验免登录（V4 志愿者证批），未知令牌回业务错误而不是「请先登录」；</li>
     *   <li>文件分享免登录（V4 系统治理批），未知令牌回业务错误而不是 500。</li>
     * </ol>
     * <p>以及最直接的一条：{@code /actuator/info} 报出构建版本、构建时间与数据库迁移版本号（旧包上这个端点不存在）。
     * 这个端点公网可达，所以同时钉住它<b>不带</b>连接串与口令。</p>
     */
    @Test
    void deployProbes_tellANewBuildFromAnOldOne() {
        ApiStressClient c = new ApiStressClient(port);
        String vol = verifiedVolunteer(c, "smoke-probe-" + Long.toString(System.nanoTime(), 36));

        ApiStressClient.Resp missing = c.get("/v/__no_such_endpoint__", vol, "不存在的路径");
        assertEquals(404, missing.http(), missing.toString());

        ApiStressClient.Resp card = c.get("/v/user/volunteer-cards/verify?token=probe", null, "志愿者证核验");
        assertNotEquals(401, card.http(), "核验必须免登录：" + card);
        assertTrue(card.http() < 500, card.toString());

        ApiStressClient.Resp share = c.get("/share/files/probe", null, "文件分享");
        assertTrue(share.http() < 500, share.toString());
        assertNotEquals(401, share.http(), share.toString());

        String info = c.rawGet("/actuator/info");
        String expected = jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success = 1 "
                + "AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1", String.class);
        assertTrue(info.contains("\"schemaVersion\":\"" + expected + "\""), "迁移版本号要报出来：" + info);
        assertTrue(info.contains("\"build\""), "构建版本与时间要报出来：" + info);
        for (String secret : List.of("jdbc:", "password", "secret", "access-key")) {
            assertTrue(!info.toLowerCase().contains(secret), "公网可达的端点不许带 " + secret + "：" + info);
        }
        // git 一节只在带 .git 的目录里打包时才有；有的话只许是提交号、提交时间、分支与 dirty——完整清单里有提交人邮箱与提交说明
        for (String key : List.of("\"user\"", "\"email\"", "\"message\"", "\"remote\"", "\"tags\"")) {
            assertTrue(!info.contains(key), "公网可达的端点不许带 git 的 " + key + "：" + info);
        }
    }

    // ---------------- helpers ----------------

    private List<String> getPatterns() {
        TreeSet<String> out = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            if (!info.getMethodsCondition().getMethods().contains(RequestMethod.GET)) {
                continue;
            }
            out.addAll(info.getPatternValues());
        }
        return new ArrayList<>(out);
    }

    private static Map<String, String> personas(String... nameTokenPairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameTokenPairs.length; i += 2) {
            m.put(nameTokenPairs[i], nameTokenPairs[i + 1]);
        }
        return m;
    }

    private static String guest(ApiStressClient c, String key) {
        ApiStressClient.Resp r = c.post("/v/auth/login/dev", null, Map.of("key", key, "registered", false), "游客登录");
        ApiStressClient.requireOk(r, "游客登录");
        return r.data().get("token").asText();
    }

    /** 已实名且绑了手机号：社区要求「已验手机号」才能看，开发登录造的身份没有手机号。 */
    private String verifiedVolunteer(ApiStressClient c, String key) {
        String token = c.devLogin(key);
        String phone = "136" + String.format("%08d", System.nanoTime() % 100_000_000L);
        jdbc.update("UPDATE volunteer SET phone = ?, phone_hash = ? WHERE openid = ?",
                cryptoUtil.encrypt(phone), cryptoUtil.hashPhone(phone), "dev:" + key);
        return token;
    }

    private static String noPermissionAdmin(ApiStressClient c, String root, String username) {
        ApiStressClient.Resp r = c.post("/a/organization/sub-accounts", root, Map.of("username", username,
                "password", "pass1234", "realName", "无权限测试", "department", "秘书部"), "建子账号");
        ApiStressClient.requireOk(r, "建子账号");
        return c.adminLogin(username, "pass1234");
    }

    private static String enterprise(ApiStressClient c, String root, String tag) {
        Map<String, Object> ent = new HashMap<>();
        ent.put("name", "冒烟企业-" + tag);
        ent.put("creditCode", CreditCodeUtil.randomCreditCode());
        ent.put("leaderName", "负责人");
        ent.put("leaderPhone", "158" + String.format("%08d", System.nanoTime() % 100_000_000L));
        ent.put("username", "smoke_" + tag);
        ent.put("password", "pass1234");
        ApiStressClient.requireOk(c.post("/a/enterprise/enterprises", root, ent, "后台建企业"), "后台建企业");
        ApiStressClient.Resp login = c.post("/e/auth/login", null,
                Map.of("username", "smoke_" + tag, "password", "pass1234"), "企业登录");
        ApiStressClient.requireOk(login, "企业登录");
        return login.data().get("token").asText();
    }
}
