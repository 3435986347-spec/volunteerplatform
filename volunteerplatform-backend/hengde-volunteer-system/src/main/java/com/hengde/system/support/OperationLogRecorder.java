package com.hengde.system.support;

import com.hengde.auth.service.AdminQueryService;
import com.hengde.common.utils.IpUtil;
import com.hengde.system.constant.SystemCodes;
import com.hengde.system.entity.SysOperationLog;
import com.hengde.system.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 把一次请求变成一条日志（V4 系统治理批）。
 *
 * <p>姓名与部门在<b>记录的那一刻</b>取并存成快照：账号日后改名、换部门甚至被删，日志仍要说得清当时是谁。
 * 落库是异步的（{@link OperationLogService#record}），所以这里多一次按主键的查询不会拖住业务请求。</p>
 *
 * @author hengde
 */
@Component
public class OperationLogRecorder {

    private OperationLogService operationLogService;
    private AdminQueryService adminQueryService;

    @Autowired
    public void setOperationLogService(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    @Autowired
    public void setAdminQueryService(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    /** 一次后台操作（写操作或敏感读，由 api 的拦截器调）。 */
    public void recordOperation(HttpServletRequest request, Integer actorType, Long actorId, String action,
                                boolean success, String errorMsg, long costMs) {
        SysOperationLog entry = base(request);
        entry.setLogType(SystemCodes.LOG_OPERATION);
        entry.setActorType(actorType == null ? SystemCodes.ACTOR_ANONYMOUS : actorType);
        entry.setActorId(actorId);
        entry.setAction(cut(action, 128));
        entry.setSuccess(success ? 1 : 0);
        entry.setErrorMsg(cut(errorMsg, 255));
        entry.setCostMs((int) Math.min(costMs, Integer.MAX_VALUE));
        fillActor(entry);
        operationLogService.record(entry);
    }

    /** 一次页面访问（前端上报）。 */
    public void recordPageView(HttpServletRequest request, Long adminId, String page, String path) {
        SysOperationLog entry = base(request);
        entry.setLogType(SystemCodes.LOG_PAGE_VIEW);
        entry.setActorType(SystemCodes.ACTOR_ADMIN);
        entry.setActorId(adminId);
        entry.setAction(cut(page, 128));
        entry.setUri(cut(path, 255));
        entry.setMethod(null);
        entry.setQuery(null);
        entry.setSuccess(1);
        fillActor(entry);
        operationLogService.record(entry);
    }

    private void fillActor(SysOperationLog entry) {
        if (entry.getActorId() == null || !Integer.valueOf(SystemCodes.ACTOR_ADMIN).equals(entry.getActorType())) {
            return;
        }
        entry.setActorName(adminQueryService.listNamesByIds(List.of(entry.getActorId())).get(entry.getActorId()));
        entry.setDepartment(adminQueryService.departmentOf(entry.getActorId()));
    }

    private static SysOperationLog base(HttpServletRequest request) {
        SysOperationLog entry = new SysOperationLog();
        entry.setCreateTime(LocalDateTime.now().withNano(0));
        if (request != null) {
            entry.setMethod(request.getMethod());
            entry.setUri(cut(request.getRequestURI(), 255));
            // 只记查询串，不记请求体：改密码、重置密码那些请求体里就是密码本身
            entry.setQuery(cut(request.getQueryString(), 512));
            entry.setIp(cut(IpUtil.getClientIp(request), 64));
            entry.setUserAgent(cut(request.getHeader("User-Agent"), 255));
        }
        return entry;
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
