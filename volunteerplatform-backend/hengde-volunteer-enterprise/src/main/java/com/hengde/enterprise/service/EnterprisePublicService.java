package com.hengde.enterprise.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.enterprise.constant.EnterpriseStatus;
import com.hengde.enterprise.dao.EnterpriseAccountMapper;
import com.hengde.enterprise.entity.EnterpriseAccount;
import com.hengde.enterprise.vo.EnterpriseVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 志愿者端「爱心企业」板块（Row 15：编号、照片、店名、地址、电话、详情）：只列正常的企业，不给信用代码、负责人与账号。
 *
 * @author hengde
 */
@Service
public class EnterprisePublicService {

    static final int INTRO_BRIEF = 60;

    private EnterpriseAccountMapper accountMapper;

    @Autowired
    public void setAccountMapper(EnterpriseAccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    public PageResult<EnterpriseVOs.Card> list(PageQuery query, String keyword) {
        IPage<EnterpriseAccount> page = accountMapper.selectPage(query.toPage(), Wrappers.<EnterpriseAccount>lambdaQuery()
                .eq(EnterpriseAccount::getStatus, EnterpriseStatus.NORMAL)
                .like(StringUtils.hasText(keyword), EnterpriseAccount::getName, keyword == null ? null : keyword.trim())
                .orderByAsc(EnterpriseAccount::getId));
        return PageResult.of(page.convert(a -> card(a, true)));
    }

    public EnterpriseVOs.Card detail(Long id) {
        EnterpriseAccount a = id == null ? null : accountMapper.selectById(id);
        if (a == null || !Objects.equals(a.getStatus(), EnterpriseStatus.NORMAL)) {
            throw new BusinessException("企业不存在");
        }
        return card(a, false);
    }

    static EnterpriseVOs.Card card(EnterpriseAccount a, boolean brief) {
        EnterpriseVOs.Card vo = new EnterpriseVOs.Card();
        vo.setId(a.getId());
        vo.setName(a.getName());
        vo.setLogoUrl(a.getLogoUrl());
        vo.setAddress(a.getAddress());
        vo.setContactPhone(a.getContactPhone());
        String intro = a.getIntro();
        vo.setIntro(brief && intro != null && intro.codePointCount(0, intro.length()) > INTRO_BRIEF
                ? intro.substring(0, intro.offsetByCodePoints(0, INTRO_BRIEF)) + "…" : intro);
        return vo;
    }
}
