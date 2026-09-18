package com.hengde.enterprise.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.utils.MaskUtil;
import com.hengde.donate.service.MallOrderService;
import com.hengde.donate.vo.SponsorPickedOrderView;
import com.hengde.enterprise.constant.EnterpriseStatus;
import com.hengde.enterprise.dao.EnterpriseReviewMapper;
import com.hengde.enterprise.dto.EnterpriseReviewDTOs;
import com.hengde.enterprise.entity.EnterpriseAccount;
import com.hengde.enterprise.entity.EnterpriseReview;
import com.hengde.enterprise.vo.EnterpriseReviewVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 赞助商评价（V4 爱心企业批·社区段，Row 74）：志愿者凭一张<b>自己的、已领取的、这家企业赞助的</b>兑换单评价一次；后台可屏蔽 / 恢复 / 删除。
 *
 * <p><b>资格判定只认兑换单</b>（donate 的只读查询 {@code findPickedSponsorOrder}）：评价的是这一次兑换的体验，
 * 没兑换过就没有可评的东西；一单一评由生成列唯一键 {@code uk_active_order} 兜底（删掉之后可以重评）。</p>
 *
 * <p><b>屏蔽不是删除</b>：屏蔽只是对外藏起来（作者与后台仍看得到，可恢复），删除是逻辑删除——Row 74 原文就是两个动作。
 * 公开列表只列正常的；企业自己与后台看得到被屏蔽的那几条，否则「为什么我的评价不见了」没人答得上来。</p>
 *
 * @author hengde
 */
@Service
public class EnterpriseReviewService {

    private EnterpriseReviewMapper reviewMapper;
    private EnterpriseQueryService enterpriseQueryService;
    private MallOrderService mallOrderService;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setReviewMapper(EnterpriseReviewMapper reviewMapper) {
        this.reviewMapper = reviewMapper;
    }

    @Autowired
    public void setEnterpriseQueryService(EnterpriseQueryService enterpriseQueryService) {
        this.enterpriseQueryService = enterpriseQueryService;
    }

    @Autowired
    public void setMallOrderService(MallOrderService mallOrderService) {
        this.mallOrderService = mallOrderService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    /** 志愿者评价赞助商。 */
    public Long create(Long volunteerId, Long enterpriseId, EnterpriseReviewDTOs.Create dto) {
        if (volunteerId == null) {
            throw new BusinessException("请先登录");
        }
        EnterpriseAccount a = enterpriseQueryService.find(enterpriseId);
        if (a == null || !Objects.equals(a.getStatus(), EnterpriseStatus.NORMAL)) {
            throw new BusinessException("企业不存在或当前不可用");
        }
        if (dto == null || dto.getOrderId() == null) {
            throw new BusinessException("请指定兑换单");
        }
        if (dto.getRating() == null || dto.getRating() < 1 || dto.getRating() > 5) {
            throw new BusinessException("评分是 1~5");
        }
        SponsorPickedOrderView order = mallOrderService.findPickedSponsorOrder(dto.getOrderId(), volunteerId);
        if (order == null || !Objects.equals(order.getEnterpriseId(), enterpriseId)) {
            throw new BusinessException("只能评价自己已领取的、这家企业赞助的兑换单");
        }
        String content = StringUtils.hasText(dto.getContent()) ? dto.getContent().trim() : null;
        if (content != null && content.codePointCount(0, content.length()) > 500) {
            throw new BusinessException("评价不超过 500 字");
        }
        EnterpriseReview r = new EnterpriseReview();
        r.setEnterpriseId(enterpriseId);
        r.setVolunteerId(volunteerId);
        r.setOrderId(dto.getOrderId());
        r.setRating(dto.getRating());
        r.setContent(content);
        r.setStatus(EnterpriseReview.NORMAL);
        try {
            reviewMapper.insert(r);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("这张兑换单已经评价过了");
        }
        return r.getId();
    }

    /** 公开列表（企业主页）：只列正常的。 */
    public PageResult<EnterpriseReviewVO> listPublic(Long enterpriseId, PageQuery query) {
        return page(Wrappers.<EnterpriseReview>lambdaQuery()
                .eq(EnterpriseReview::getEnterpriseId, enterpriseId)
                .eq(EnterpriseReview::getStatus, EnterpriseReview.NORMAL)
                .orderByDesc(EnterpriseReview::getId), query, false);
    }

    /** 企业自己看（含被屏蔽的，标出来）。 */
    public PageResult<EnterpriseReviewVO> listForEnterprise(Long enterpriseId, PageQuery query) {
        return page(Wrappers.<EnterpriseReview>lambdaQuery()
                .eq(EnterpriseReview::getEnterpriseId, enterpriseId)
                .orderByDesc(EnterpriseReview::getId), query, true);
    }

    /** 我写过的评价。 */
    public PageResult<EnterpriseReviewVO> listMine(Long volunteerId, PageQuery query) {
        return page(Wrappers.<EnterpriseReview>lambdaQuery()
                .eq(EnterpriseReview::getVolunteerId, volunteerId)
                .orderByDesc(EnterpriseReview::getId), query, true);
    }

    /** 后台列表（可按企业与状态筛）。 */
    public PageResult<EnterpriseReviewVO> listForAdmin(Long enterpriseId, Integer status, PageQuery query) {
        return page(Wrappers.<EnterpriseReview>lambdaQuery()
                .eq(enterpriseId != null, EnterpriseReview::getEnterpriseId, enterpriseId)
                .eq(status != null, EnterpriseReview::getStatus, status)
                .orderByDesc(EnterpriseReview::getId), query, true);
    }

    /** 屏蔽（对外藏起来）。 */
    public void hide(Long id, String reason, Long adminId) {
        requireOperator(adminId);
        String r = StringUtils.hasText(reason) ? reason.trim() : null;
        if (r != null && r.length() > 255) {
            throw new BusinessException("原因不超过 255 字");
        }
        int rows = reviewMapper.update(null, Wrappers.<EnterpriseReview>lambdaUpdate()
                .eq(EnterpriseReview::getId, id)
                .eq(EnterpriseReview::getStatus, EnterpriseReview.NORMAL)
                .set(EnterpriseReview::getStatus, EnterpriseReview.HIDDEN)
                .set(EnterpriseReview::getHiddenBy, adminId)
                .set(EnterpriseReview::getHiddenTime, LocalDateTime.now().withNano(0))
                .set(EnterpriseReview::getHiddenReason, r)
                .set(EnterpriseReview::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("评价不存在或已被屏蔽");
        }
    }

    /** 恢复显示。 */
    public void show(Long id, Long adminId) {
        requireOperator(adminId);
        int rows = reviewMapper.update(null, Wrappers.<EnterpriseReview>lambdaUpdate()
                .eq(EnterpriseReview::getId, id)
                .eq(EnterpriseReview::getStatus, EnterpriseReview.HIDDEN)
                .set(EnterpriseReview::getStatus, EnterpriseReview.NORMAL)
                .set(EnterpriseReview::getHiddenBy, null)
                .set(EnterpriseReview::getHiddenTime, null)
                .set(EnterpriseReview::getHiddenReason, null)
                .set(EnterpriseReview::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("评价不存在或未被屏蔽");
        }
    }

    /** 删除（逻辑删除；删掉之后这张兑换单可以重新评价）。 */
    public void delete(Long id, Long adminId) {
        requireOperator(adminId);
        if (id == null || reviewMapper.deleteById(id) != 1) {
            throw new BusinessException("评价不存在");
        }
    }

    // ================= 内部 =================

    private PageResult<EnterpriseReviewVO> page(LambdaQueryWrapper<EnterpriseReview> w, PageQuery query, boolean withStatus) {
        IPage<EnterpriseReview> page = reviewMapper.selectPage(query.toPage(), w);
        List<EnterpriseReview> rows = page.getRecords();
        Map<Long, String> names = rows.isEmpty() ? Map.of() : volunteerQueryService.listNamesByIds(
                rows.stream().map(EnterpriseReview::getVolunteerId).distinct().toList());
        List<EnterpriseReviewVO> out = new ArrayList<>(rows.size());
        java.util.Map<Long, String> entNames = new java.util.HashMap<>();
        for (EnterpriseReview r : rows) {
            EnterpriseReviewVO vo = new EnterpriseReviewVO();
            vo.setId(r.getId());
            vo.setEnterpriseId(r.getEnterpriseId());
            vo.setEnterpriseName(entNames.computeIfAbsent(r.getEnterpriseId(), id -> {
                EnterpriseAccount a = enterpriseQueryService.find(id);
                return a == null ? null : a.getName();
            }));
            vo.setVolunteerMaskedName(MaskUtil.maskName(names.get(r.getVolunteerId())));
            vo.setRating(r.getRating());
            vo.setContent(r.getContent());
            if (withStatus) {
                vo.setStatus(r.getStatus());
                vo.setHiddenReason(r.getHiddenReason());
            }
            vo.setCreateTime(r.getCreateTime());
            out.add(vo);
        }
        return PageResult.of(out, page.getTotal(), page.getCurrent(), page.getSize());
    }

    private static void requireOperator(Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
    }
}
