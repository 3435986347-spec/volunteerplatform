package com.hengde.enterprise.service;

import cn.hutool.core.util.CreditCodeUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.oss.FileStorageService;
import com.hengde.enterprise.constant.EnterpriseStatus;
import com.hengde.enterprise.entity.EnterpriseAccount;
import com.hengde.enterprise.vo.EnterpriseVOs;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 爱心企业几处服务共用的规则（无状态）：信用代码规范化与校验、唯一键撞键翻译、头像只收本系统上传、出参拼装。
 *
 * @author hengde
 */
final class EnterpriseSupport {

    /** 企业头像 / 照片上传目录（企业端 {@code POST /e/files/image}、后台 {@code dir=enterprise}） */
    static final String UPLOAD_DIR = "enterprise";

    private EnterpriseSupport() {
    }

    /** 去空白、转大写后按 GB 32100-2015 校验位校验（Q4：只做格式校验 + 人工审核，不调第三方核验）。 */
    static String normalizeCreditCode(String raw) {
        String code = raw == null ? "" : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!CreditCodeUtil.isCreditCode(code)) {
            throw new BusinessException("统一社会信用代码不正确，请核对（18 位，含校验位）");
        }
        return code;
    }

    static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    static String requireText(String s, String message) {
        if (!StringUtils.hasText(s)) {
            throw new BusinessException(message);
        }
        return s.trim();
    }

    static void requireOwnImage(FileStorageService storage, String url) {
        if (url != null && !storage.isOwnUpload(url, UPLOAD_DIR)) {
            throw new BusinessException("企业头像无效，请重新上传");
        }
    }

    /** 唯一键撞键翻译成人话；不是这两个键的原样抛出。 */
    static BusinessException duplicate(DuplicateKeyException e) {
        String msg = String.valueOf(e.getMessage());
        if (msg.contains("uk_active_username")) {
            return new BusinessException("这个登录账号已经被注册了，请换一个");
        }
        if (msg.contains("uk_active_credit_code")) {
            return new BusinessException("这个统一社会信用代码已经入驻过了；如需找回账号请联系平台");
        }
        throw e;
    }

    static EnterpriseVOs.Account toAccount(EnterpriseAccount a, String leaderPhone, String auditByName) {
        EnterpriseVOs.Account vo = new EnterpriseVOs.Account();
        vo.setId(a.getId());
        vo.setName(a.getName());
        vo.setCreditCode(a.getCreditCode());
        vo.setLogoUrl(a.getLogoUrl());
        vo.setIntro(a.getIntro());
        vo.setAddress(a.getAddress());
        vo.setContactPhone(a.getContactPhone());
        vo.setLeaderName(a.getLeaderName());
        vo.setLeaderPhone(leaderPhone);
        vo.setUsername(a.getUsername());
        vo.setStatus(a.getStatus());
        vo.setStatusLabel(EnterpriseStatus.label(a.getStatus()));
        vo.setSource(a.getSource());
        vo.setSubmitTime(a.getSubmitTime());
        vo.setRejectReason(a.getRejectReason());
        vo.setAuditByName(auditByName);
        vo.setAuditTime(a.getAuditTime());
        vo.setPauseReason(a.getPauseReason());
        vo.setPausedTime(a.getPausedTime());
        vo.setLastLoginTime(a.getLastLoginTime());
        vo.setCreateTime(a.getCreateTime());
        return vo;
    }
}
