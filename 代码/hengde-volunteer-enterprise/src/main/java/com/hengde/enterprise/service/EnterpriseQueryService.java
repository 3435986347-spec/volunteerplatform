package com.hengde.enterprise.service;

import com.hengde.enterprise.dao.EnterpriseAccountMapper;
import com.hengde.enterprise.entity.EnterpriseAccount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 爱心企业只读查询（api 的企业端闸门每个请求查一次状态；后续批次的商品 / 发帖校验企业是否正常）。
 *
 * @author hengde
 */
@Service
public class EnterpriseQueryService {

    private EnterpriseAccountMapper accountMapper;

    @Autowired
    public void setAccountMapper(EnterpriseAccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    /** @return 状态；账号不存在或已删除返回 null */
    public Integer statusOf(Long enterpriseId) {
        if (enterpriseId == null) {
            return null;
        }
        EnterpriseAccount a = accountMapper.selectById(enterpriseId);
        return a == null ? null : a.getStatus();
    }

    /** 未删除的账号（任意状态）；没有为 null。 */
    public EnterpriseAccount find(Long enterpriseId) {
        return enterpriseId == null ? null : accountMapper.selectById(enterpriseId);
    }
}
