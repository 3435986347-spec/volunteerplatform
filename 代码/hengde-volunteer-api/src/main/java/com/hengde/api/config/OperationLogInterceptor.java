package com.hengde.api.config;

import com.hengde.auth.config.StpAdminUtil;
import com.hengde.system.constant.SystemCodes;
import com.hengde.system.support.OperationLogRecorder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 操作日志拦截器（V4 系统治理批，Row 62「所有操作都要记录，避免出现信息泄露追责难题」）。
 *
 * <p><b>记什么</b>：{@code /a/**} 的<b>全部写操作</b>（POST / PUT / PATCH / DELETE）+ 显式登记的<b>敏感读</b>
 * （导出、看明文手机号、看聊天记录、看到家详细地址）。**敏感读是白名单**——把所有 GET 都记下来，日志会被列表刷屏，
 * 真正要追的那几条反而找不到。</p>
 *
 * <p><b>动作名从 Swagger 的 {@code @Operation(summary)} 取</b>：那句话本来就是给人看的，
 * 另写一份「动作名表」必然与端点漂开（本项目在别处吃过「两份口径」的亏）。</p>
 *
 * <p><b>失败也记</b>（{@code success=0} + 原因）：被拒绝的越权尝试恰恰是追责时最要看的。
 * 记录本身只入队，落库是异步的——日志绝不能把业务请求拖住（V4规划 D9）。</p>
 *
 * @author hengde
 */
@Component
public class OperationLogInterceptor implements HandlerInterceptor {

    private static final String START = "hengde.log.start";
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /**
     * 敏感读白名单：这些 GET 虽然不改数据，但看到的是别人的隐私或协会的底账。
     *
     * <p>新增这类端点时要往这里加一条——漏加的后果是「谁看过」查不到，而那正是 Row 62 要解决的问题。</p>
     */
    private static final List<String> SENSITIVE_READS = List.of(
            "/a/**/export",                 // 各域的 xlsx 导出（名单、报名、物资、企业…）
            "/a/user/volunteers/*",         // 志愿者详情（明文手机号、身份证尾号）
            "/a/social/chats/**",           // 聊天记录（Row 23 F）
            "/a/system/logs",               // 谁翻过日志本身也要留痕
            "/a/activity/activities/*/home-confirmations", // 到家详细地址（Row 63）
            "/a/honor/certificates/*/download",            // 证书下载（短期签名 URL）
            "/share/files/*"                // 文件分享链接被打开
    );

    private OperationLogRecorder recorder;

    @Autowired
    public void setRecorder(OperationLogRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!shouldLog(request.getRequestURI(), request.getMethod())) {
            return;
        }
        Object started = request.getAttribute(START);
        long cost = started instanceof Long s ? System.currentTimeMillis() - s : 0L;
        boolean success = ex == null && response.getStatus() < 400;
        recorder.recordOperation(request, actorType(), actorId(), actionOf(request, handler), success,
                ex == null ? null : ex.getMessage(), cost);
    }

    /** 这条路径 + 方法要不要记（静态、只看字符串，用例可以直接问它，不必起一个容器）。 */
    public static boolean shouldLog(String uri, String method) {
        String path = stripContext(uri);
        if (path.startsWith("/a/") && !"GET".equalsIgnoreCase(method)) {
            return true;
        }
        if (!"GET".equalsIgnoreCase(method)) {
            return false;
        }
        for (String pattern : SENSITIVE_READS) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** context-path 由 server 配置加在前面，登记的清单按业务路径写。 */
    private static String stripContext(String uri) {
        String path = uri == null ? "" : uri;
        return path.startsWith("/api/") ? path.substring(4) : path;
    }

    private static String actionOf(HttpServletRequest request, Object handler) {
        if (handler instanceof HandlerMethod hm) {
            io.swagger.v3.oas.annotations.Operation op =
                    hm.getMethodAnnotation(io.swagger.v3.oas.annotations.Operation.class);
            if (op != null && !op.summary().isBlank()) {
                return op.summary();
            }
            return hm.getBeanType().getSimpleName() + "." + hm.getMethod().getName();
        }
        return request.getMethod() + " " + stripContext(request.getRequestURI());
    }

    private static Integer actorType() {
        return StpAdminUtil.STP_LOGIC.isLogin() ? SystemCodes.ACTOR_ADMIN : SystemCodes.ACTOR_ANONYMOUS;
    }

    private static Long actorId() {
        try {
            return StpAdminUtil.STP_LOGIC.isLogin()
                    ? Long.parseLong(StpAdminUtil.STP_LOGIC.getLoginId().toString()) : null;
        } catch (RuntimeException e) {
            // 没登录、token 坏掉都算「不知道是谁」——日志照记，正是要留下这次尝试
            return null;
        }
    }
}
