package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.constant.DonationFlow;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.dao.DonateDonationMapper;
import com.hengde.donate.dto.ShipmentRegisterDTO;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.vo.DonateFlowVOs;
import org.springframework.transaction.annotation.Transactional;
import com.hengde.donate.dao.DonatePairMappers.DonateCrowdfundMapper;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.entity.DonateCrowdfund;
import com.hengde.donate.vo.PairVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 项目众筹（Row 16，V3 结对批）：项目管理与进度展示。
 *
 * <p><b>结对批做了项目管理这一半</b>；捐款批起 {@code raisedAmount} 由 {@code DonationService} 在<b>到账</b>时写入、
 * 退款时减回，捐款人数按已到账的捐款去重现算（不存列）。</p>
 *
 * @author hengde
 */
@Service
public class CrowdfundService {

    private DonateCrowdfundMapper crowdfundMapper;
    private DonateDonationMapper donationMapper;
    private DonateShipmentService shipmentService;

    @Autowired
    public void setShipmentService(DonateShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @Autowired
    public void setDonationMapper(DonateDonationMapper donationMapper) {
        this.donationMapper = donationMapper;
    }

    @Autowired
    public void setCrowdfundMapper(DonateCrowdfundMapper crowdfundMapper) {
        this.crowdfundMapper = crowdfundMapper;
    }

    public Long create(PairDTOs.CrowdfundSave dto, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        DonateCrowdfund c = new DonateCrowdfund();
        apply(c, dto);
        c.setStatus(PairFlow.CROWDFUND_DRAFT);
        c.setRaisedAmount(BigDecimal.ZERO);
        c.setCreateBy(adminId);
        crowdfundMapper.insert(c);
        return c.getId();
    }

    /** 修改项目（已结束的不能改；条件写进 UPDATE 的 WHERE）。 */
    public void update(Long id, PairDTOs.CrowdfundSave dto) {
        DonateCrowdfund c = new DonateCrowdfund();
        apply(c, dto);
        int rows = crowdfundMapper.update(null, Wrappers.<DonateCrowdfund>lambdaUpdate()
                .eq(DonateCrowdfund::getId, id)
                .in(DonateCrowdfund::getStatus, PairFlow.CROWDFUND_DRAFT, PairFlow.CROWDFUND_OPEN)
                .set(DonateCrowdfund::getTitle, c.getTitle())
                .set(DonateCrowdfund::getCoverUrl, c.getCoverUrl())
                .set(DonateCrowdfund::getDetail, c.getDetail())
                .set(DonateCrowdfund::getTargetAmount, c.getTargetAmount())
                .set(DonateCrowdfund::getStartTime, c.getStartTime())
                .set(DonateCrowdfund::getEndTime, c.getEndTime())
                .set(DonateCrowdfund::getAcceptMoney, c.getAcceptMoney())
                .set(DonateCrowdfund::getAcceptGoods, c.getAcceptGoods())
                .set(DonateCrowdfund::getGoodsNeeded, c.getGoodsNeeded())
                .set(DonateCrowdfund::getRecvName, c.getRecvName())
                .set(DonateCrowdfund::getRecvPhone, c.getRecvPhone())
                .set(DonateCrowdfund::getRecvAddress, c.getRecvAddress())
                .set(DonateCrowdfund::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("已结束的项目不能再修改（当前："
                    + PairFlow.crowdfundLabel(require(id).getStatus()) + "）");
        }
    }

    public void publish(Long id) {
        transit(id, PairFlow.CROWDFUND_DRAFT, PairFlow.CROWDFUND_OPEN, "只有草稿状态的项目可以上架");
    }

    public void end(Long id) {
        transit(id, PairFlow.CROWDFUND_OPEN, PairFlow.CROWDFUND_ENDED, "只有进行中的项目可以结束");
    }

    /** 删除（仅草稿；上过架的项目请用「结束」，否则志愿者那边的入口会凭空消失）。 */
    public void delete(Long id) {
        int rows = crowdfundMapper.delete(Wrappers.<DonateCrowdfund>lambdaQuery()
                .eq(DonateCrowdfund::getId, id)
                .eq(DonateCrowdfund::getStatus, PairFlow.CROWDFUND_DRAFT));
        if (rows != 1) {
            throw new BusinessException("只有草稿状态的项目可以删除（当前："
                    + PairFlow.crowdfundLabel(require(id).getStatus()) + "）");
        }
    }

    public PageResult<PairVOs.Crowdfund> listForAdmin(PageQuery query, Integer status, String keyword) {
        IPage<DonateCrowdfund> page = crowdfundMapper.selectPage(query.toPage(),
                Wrappers.<DonateCrowdfund>lambdaQuery()
                        .eq(status != null, DonateCrowdfund::getStatus, status)
                        .like(StringUtils.hasText(keyword), DonateCrowdfund::getTitle,
                                keyword == null ? null : keyword.trim())
                        .orderByDesc(DonateCrowdfund::getId));
        return withDonors(PageResult.of(page.convert(c -> toVO(c, false))));
    }

    public PairVOs.Crowdfund detailForAdmin(Long id) {
        return withDonor(toVO(require(id), true));
    }

    /**
     * 志愿者端列表（Row 16 三个页签）：{@code tab} 0全部 / 1进行中 / 2已结束。
     * 「全部」也不含草稿——草稿是还没定稿的东西。
     */
    public PageResult<PairVOs.Crowdfund> listForVolunteer(PageQuery query, Integer tab) {
        Integer status = null;
        if (tab != null && tab != 0) {
            if (tab != PairFlow.CROWDFUND_OPEN && tab != PairFlow.CROWDFUND_ENDED) {
                throw new BusinessException("tab 只能是 0全部 / 1进行中 / 2已结束");
            }
            status = tab;
        }
        IPage<DonateCrowdfund> page = crowdfundMapper.selectPage(query.toPage(),
                Wrappers.<DonateCrowdfund>lambdaQuery()
                        .ne(DonateCrowdfund::getStatus, PairFlow.CROWDFUND_DRAFT)
                        .eq(status != null, DonateCrowdfund::getStatus, status)
                        .orderByDesc(DonateCrowdfund::getId));
        return withDonors(PageResult.of(page.convert(c -> toVO(c, false))));
    }

    public PairVOs.Crowdfund detailForVolunteer(Long id) {
        DonateCrowdfund c = id == null ? null : crowdfundMapper.selectById(id);
        if (c == null || Objects.equals(c.getStatus(), PairFlow.CROWDFUND_DRAFT)) {
            throw new BusinessException("项目不存在");
        }
        return withDonor(toVO(c, true));
    }

    /** 捐款人数：已到账的捐款去重现算，一页一次查（不逐条查）。 */
    private PageResult<PairVOs.Crowdfund> withDonors(PageResult<PairVOs.Crowdfund> page) {
        List<Long> ids = page.getRecords().stream().map(PairVOs.Crowdfund::getId).toList();
        Map<Long, Integer> counts = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> row : donationMapper.countDonors(DonationFlow.BIZ_CROWDFUND, ids, DonationFlow.PAID)) {
                counts.put(((Number) row.get("projectId")).longValue(), ((Number) row.get("cnt")).intValue());
            }
        }
        page.getRecords().forEach(vo -> vo.setDonorCount(counts.getOrDefault(vo.getId(), 0)));
        return page;
    }

    private PairVOs.Crowdfund withDonor(PairVOs.Crowdfund vo) {
        List<Map<String, Object>> rows = donationMapper.countDonors(DonationFlow.BIZ_CROWDFUND, List.of(vo.getId()),
                DonationFlow.PAID);
        vo.setDonorCount(rows.isEmpty() ? 0 : ((Number) rows.get(0).get("cnt")).intValue());
        return vo;
    }

    /**
     * 众筹捐物（Row 16）：登记寄出「物资名称 + 快递公司 + 单号」。物资流转复用捐书批的地基（运单 {@code biz_type = 3}）。
     *
     * <p>资格：项目在募集中、接受捐物；实名要求与「一个快递单号只登记一次」由 {@code createShipment} 统一把关。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public DonateFlowVOs.Shipment registerGoods(Long crowdfundId, Long volunteerId, ShipmentRegisterDTO dto) {
        DonateCrowdfund c = require(crowdfundId);
        LocalDateTime now = LocalDateTime.now();
        if (!Objects.equals(c.getStatus(), PairFlow.CROWDFUND_OPEN)
                || (c.getStartTime() != null && now.isBefore(c.getStartTime()))
                || (c.getEndTime() != null && !now.isBefore(c.getEndTime()))) {
            throw new BusinessException("这个项目当前不在募集中");
        }
        if (!Objects.equals(c.getAcceptGoods(), 1)) {
            throw new BusinessException("这个项目不接受捐物");
        }
        return shipmentService.createShipment(DonateFlow.BIZ_CROWDFUND_GOODS, crowdfundId, volunteerId, dto);
    }

    public DonateCrowdfund require(Long id) {
        DonateCrowdfund c = id == null ? null : crowdfundMapper.selectById(id);
        if (c == null) {
            throw new BusinessException("项目不存在");
        }
        return c;
    }

    private void transit(Long id, int from, int to, String rejectMessage) {
        int rows = crowdfundMapper.update(null, Wrappers.<DonateCrowdfund>lambdaUpdate()
                .eq(DonateCrowdfund::getId, id)
                .eq(DonateCrowdfund::getStatus, from)
                .set(DonateCrowdfund::getStatus, to)
                .set(DonateCrowdfund::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException(rejectMessage + "（当前："
                    + PairFlow.crowdfundLabel(require(id).getStatus()) + "）");
        }
    }

    private static void apply(DonateCrowdfund c, PairDTOs.CrowdfundSave dto) {
        if (dto == null || !StringUtils.hasText(dto.getTitle())) {
            throw new BusinessException("请填写项目名称");
        }
        if (dto.getTargetAmount() == null || dto.getTargetAmount().signum() <= 0) {
            throw new BusinessException("预计金额须大于 0");
        }
        if (dto.getStartTime() != null && dto.getEndTime() != null
                && !dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BusinessException("结束时间须晚于开始时间");
        }
        c.setTitle(dto.getTitle().trim());
        c.setCoverUrl(dto.getCoverUrl() == null || dto.getCoverUrl().isBlank() ? null : dto.getCoverUrl().trim());
        c.setDetail(dto.getDetail() == null || dto.getDetail().isBlank() ? null : dto.getDetail().trim());
        c.setTargetAmount(dto.getTargetAmount());
        c.setStartTime(dto.getStartTime());
        c.setEndTime(dto.getEndTime());
        boolean money = !Boolean.FALSE.equals(dto.getAcceptMoney());
        boolean goods = Boolean.TRUE.equals(dto.getAcceptGoods());
        if (!money && !goods) {
            throw new BusinessException("项目至少要接受捐款或捐物中的一种");
        }
        if (goods && (!StringUtils.hasText(dto.getRecvName()) || !StringUtils.hasText(dto.getRecvPhone())
                || !StringUtils.hasText(dto.getRecvAddress()))) {
            throw new BusinessException("接受捐物请填写物资收件人、电话与地址");
        }
        if (!goods && (StringUtils.hasText(dto.getRecvName()) || StringUtils.hasText(dto.getRecvPhone())
                || StringUtils.hasText(dto.getRecvAddress()) || StringUtils.hasText(dto.getGoodsNeeded()))) {
            throw new BusinessException("不接受捐物时不用填写物资需求与收件信息");
        }
        c.setAcceptMoney(money ? 1 : 0);
        c.setAcceptGoods(goods ? 1 : 0);
        c.setGoodsNeeded(goods ? trimToNull(dto.getGoodsNeeded()) : null);
        c.setRecvName(goods ? dto.getRecvName().trim() : null);
        c.setRecvPhone(goods ? dto.getRecvPhone().trim() : null);
        c.setRecvAddress(goods ? dto.getRecvAddress().trim() : null);
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private static PairVOs.Crowdfund toVO(DonateCrowdfund c, boolean withDetail) {
        PairVOs.Crowdfund vo = new PairVOs.Crowdfund();
        vo.setId(c.getId());
        vo.setTitle(c.getTitle());
        vo.setCoverUrl(c.getCoverUrl());
        if (withDetail) {
            vo.setDetail(c.getDetail());
        }
        vo.setTargetAmount(c.getTargetAmount());
        vo.setRaisedAmount(c.getRaisedAmount());
        vo.setProgressPercent(PairProjectService.percent(c.getRaisedAmount(), c.getTargetAmount()));
        // 捐款人数由 withDonors / withDonor 批量补上
        vo.setDonorCount(0);
        vo.setAcceptMoney(!Objects.equals(c.getAcceptMoney(), 0));
        vo.setAcceptGoods(Objects.equals(c.getAcceptGoods(), 1));
        vo.setGoodsNeeded(c.getGoodsNeeded());
        vo.setRecvName(c.getRecvName());
        vo.setRecvPhone(c.getRecvPhone());
        vo.setRecvAddress(c.getRecvAddress());
        vo.setStartTime(c.getStartTime());
        vo.setEndTime(c.getEndTime());
        vo.setStatus(c.getStatus());
        vo.setStatusLabel(PairFlow.crowdfundLabel(c.getStatus()));
        vo.setCreateTime(c.getCreateTime());
        return vo;
    }
}
