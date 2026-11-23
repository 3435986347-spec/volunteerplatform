package com.hengde.enterprise.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.AdminQueryService;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.oss.FileStorageService;
import com.hengde.enterprise.constant.EnterpriseStatus;
import com.hengde.enterprise.dao.EnterpriseAccountMapper;
import com.hengde.enterprise.dto.EnterpriseDTOs;
import com.hengde.enterprise.entity.EnterpriseAccount;
import com.hengde.enterprise.vo.EnterpriseVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 企业端「我的企业」（V4 爱心企业批，Row 15「管理他的企业信息」）：看资料与审核状态 / 改资料 / 被驳回后重新提交。
 *
 * <p><b>身份信息（企业名称、信用代码、项目负责人）只在待审核或被驳回时能改</b>：审核通过之后改名等于绕过了入驻审核，
 * 要改请平台处理；头像、介绍、地址、对外电话随时可改。「只在待审核 / 驳回」写在 UPDATE 的 WHERE 里——
 * 方法开头的状态判断读的是改之前的快照，挡不住「刚被审核通过」的那一下。</p>
 *
 * @author hengde
 */
@Service
public class EnterpriseAccountService {

    private EnterpriseAccountMapper accountMapper;
    private CryptoUtil cryptoUtil;
    private FileStorageService fileStorageService;
    private AdminQueryService adminQueryService;

    @Autowired
    public void setAccountMapper(EnterpriseAccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    @Autowired
    public void setCryptoUtil(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    @Autowired
    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Autowired
    public void setAdminQueryService(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    public EnterpriseVOs.Account mine(Long enterpriseId) {
        EnterpriseAccount a = require(enterpriseId);
        String auditBy = a.getAuditBy() == null ? null : adminQueryService.listNamesByIds(List.of(a.getAuditBy())).get(a.getAuditBy());
        return EnterpriseSupport.toAccount(a, cryptoUtil.decrypt(a.getLeaderPhone()), auditBy);
    }

    public EnterpriseVOs.Account updateProfile(Long enterpriseId, EnterpriseDTOs.Profile dto) {
        EnterpriseAccount a = require(enterpriseId);
        String logo = EnterpriseSupport.trimToNull(dto.getLogoUrl());
        if (logo != null && !logo.equals(a.getLogoUrl())) {
            EnterpriseSupport.requireOwnImage(fileStorageService, logo);
        }
        LambdaUpdateWrapper<EnterpriseAccount> w = Wrappers.<EnterpriseAccount>lambdaUpdate()
                .eq(EnterpriseAccount::getId, enterpriseId)
                .set(EnterpriseAccount::getLogoUrl, logo)
                .set(EnterpriseAccount::getIntro, EnterpriseSupport.trimToNull(dto.getIntro()))
                .set(EnterpriseAccount::getAddress, EnterpriseSupport.trimToNull(dto.getAddress()))
                .set(EnterpriseAccount::getContactPhone, EnterpriseSupport.trimToNull(dto.getContactPhone()))
                .set(EnterpriseAccount::getUpdateTime, LocalDateTime.now());
        String name = EnterpriseSupport.trimToNull(dto.getName());
        String code = StringUtils.hasText(dto.getCreditCode()) ? EnterpriseSupport.normalizeCreditCode(dto.getCreditCode()) : null;
        String leader = EnterpriseSupport.trimToNull(dto.getLeaderName());
        boolean identityChange = (name != null && !name.equals(a.getName()))
                || (code != null && !code.equals(a.getCreditCode()))
                || (leader != null && !leader.equals(a.getLeaderName()));
        if (identityChange) {
            w.in(EnterpriseAccount::getStatus, EnterpriseStatus.PENDING, EnterpriseStatus.REJECTED)
                    .set(name != null, EnterpriseAccount::getName, name)
                    .set(code != null, EnterpriseAccount::getCreditCode, code)
                    .set(leader != null, EnterpriseAccount::getLeaderName, leader);
        }
        int rows;
        try {
            rows = accountMapper.update(null, w);
        } catch (DuplicateKeyException e) {
            throw EnterpriseSupport.duplicate(e);
        }
        if (rows != 1 && !identityChange) {
            throw new BusinessException("企业账号不存在");
        }
        if (rows != 1) {
            throw new BusinessException("审核通过之后企业名称、统一社会信用代码、项目负责人不能自己改，请联系平台（当前："
                    + EnterpriseStatus.label(require(enterpriseId).getStatus()) + "）");
        }
        return mine(enterpriseId);
    }

    /** 被驳回后重新提交（驳回 → 待审核）。 */
    public EnterpriseVOs.Account resubmit(Long enterpriseId) {
        int rows = accountMapper.update(null, Wrappers.<EnterpriseAccount>lambdaUpdate()
                .eq(EnterpriseAccount::getId, enterpriseId)
                .eq(EnterpriseAccount::getStatus, EnterpriseStatus.REJECTED)
                .set(EnterpriseAccount::getStatus, EnterpriseStatus.PENDING)
                .set(EnterpriseAccount::getSubmitTime, LocalDateTime.now().withNano(0))
                .set(EnterpriseAccount::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("只有被驳回的入驻申请可以重新提交（当前：" + EnterpriseStatus.label(require(enterpriseId).getStatus()) + "）");
        }
        return mine(enterpriseId);
    }

    private EnterpriseAccount require(Long id) {
        EnterpriseAccount a = id == null ? null : accountMapper.selectById(id);
        if (a == null) {
            throw new BusinessException("企业账号不存在");
        }
        return a;
    }

}
