package com.hengde.enterprise.service;

import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.dto.MallGoodsSaveDTO;
import com.hengde.donate.service.MallGoodsService;
import com.hengde.donate.vo.MallGoodsVO;
import com.hengde.enterprise.constant.EnterpriseStatus;
import com.hengde.enterprise.entity.EnterpriseAccount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 赞助商品的企业侧入口（V4 爱心企业批·商品段）：新增时取企业名称快照；后台代企业发布只允许正常的企业（Row 15 F「后台可以以企业的名义代替企业发布积分商品」）；
 * 志愿者端企业主页的「赞助商品」只给正常企业的。审核仍走商城原有的后台审核。
 *
 * @author hengde
 */
@Service
public class EnterpriseSponsorService {

    private MallGoodsService mallGoodsService;
    private EnterpriseQueryService enterpriseQueryService;

    @Autowired
    public void setMallGoodsService(MallGoodsService mallGoodsService) {
        this.mallGoodsService = mallGoodsService;
    }

    @Autowired
    public void setEnterpriseQueryService(EnterpriseQueryService enterpriseQueryService) {
        this.enterpriseQueryService = enterpriseQueryService;
    }

    /** 企业自助 / 后台代发布（都要求企业当前正常）。 */
    public Long createGoods(Long enterpriseId, MallGoodsSaveDTO dto) {
        EnterpriseAccount a = requireNormal(enterpriseId);
        return mallGoodsService.createForSponsor(dto, a.getId(), a.getName());
    }

    /** 志愿者端企业主页的赞助商品：企业须正常，商品须已上架未隐藏。 */
    public PageResult<MallGoodsVO> goodsForVolunteer(Long enterpriseId, PageQuery query) {
        requireNormal(enterpriseId);
        return mallGoodsService.listForVolunteer(query, null, enterpriseId);
    }

    private EnterpriseAccount requireNormal(Long enterpriseId) {
        EnterpriseAccount a = enterpriseQueryService.find(enterpriseId);
        if (a == null || !Objects.equals(a.getStatus(), EnterpriseStatus.NORMAL)) {
            throw new BusinessException("企业不存在或当前不可用");
        }
        return a;
    }
}
