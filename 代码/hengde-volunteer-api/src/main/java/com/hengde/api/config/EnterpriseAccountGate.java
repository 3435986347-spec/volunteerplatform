package com.hengde.api.config;

import com.hengde.auth.config.StpEnterpriseUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import com.hengde.enterprise.constant.EnterpriseStatus;
import com.hengde.enterprise.service.EnterpriseQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.Objects;

/**
 * 企业端闸门（V4 爱心企业批）：已登录的企业每个 {@code /e/**} 请求都按<b>当前</b>账号状态放行。
 *
 * <ul>
 *   <li>账号不存在或已删除、已暂停 → 踢掉登录 + 403（兜住「后台刚暂停，旧 token 还没过期」的窗口，暂停当场生效）；</li>
 *   <li>待审核 / 已驳回 → 只放行 {@link #UNAPPROVED_ALLOWED}（认证、我的企业、上传头像），其余 403「入驻审核通过后才能使用」；</li>
 *   <li>正常 → 放行。</li>
 * </ul>
 *
 * <p>与志愿者端两道闸门同样的纪律：<b>放行清单是显式的</b>，新加一个企业端接口默认要审核通过才能用，漏写清单是「用不了」而不是「漏网」。</p>
 *
 * @author hengde
 */
@Component
public class EnterpriseAccountGate {

    public static final String[] UNAPPROVED_ALLOWED = {
            "/e/auth/**",
            "/e/enterprise/profile",
            "/e/enterprise/profile/**",
            "/e/files/image",
    };

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private EnterpriseQueryService enterpriseQueryService;

    @Autowired
    public void setEnterpriseQueryService(EnterpriseQueryService enterpriseQueryService) {
        this.enterpriseQueryService = enterpriseQueryService;
    }

    public void check(String path, long enterpriseId) {
        Integer status = enterpriseQueryService.statusOf(enterpriseId);
        String problem = problemOf(path, status);
        if (problem == null) {
            return;
        }
        if (status == null || Objects.equals(status, EnterpriseStatus.PAUSED)) {
            StpEnterpriseUtil.logout(enterpriseId);
        }
        throw new BusinessException(ResultCode.FORBIDDEN.getCode(), problem);
    }

    /** 抽成静态方法只为让判定能被测试直接钉住；返回 null＝放行。 */
    static String problemOf(String path, Integer status) {
        if (status == null) {
            return "企业账号不存在或已删除，请重新登录";
        }
        if (Objects.equals(status, EnterpriseStatus.PAUSED)) {
            return "企业账号已暂停，请联系平台";
        }
        if (Objects.equals(status, EnterpriseStatus.NORMAL)) {
            return null;
        }
        for (String pattern : UNAPPROVED_ALLOWED) {
            if (MATCHER.match(pattern, path)) {
                return null;
            }
        }
        return "入驻审核通过后才能使用该功能";
    }
}
