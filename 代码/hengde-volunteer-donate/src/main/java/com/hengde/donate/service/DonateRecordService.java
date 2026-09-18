package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.constant.DonationFlow;
import com.hengde.donate.dao.DonateItemMapper;
import com.hengde.donate.dao.DonatePairMappers.DonateCrowdfundMapper;
import com.hengde.donate.dao.DonateRecordMapper;
import com.hengde.donate.dao.DonateShipmentMapper;
import com.hengde.donate.entity.DonateCrowdfund;
import com.hengde.donate.entity.DonateItem;
import com.hengde.donate.entity.DonateShipment;
import com.hengde.donate.vo.DonationVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 「我的捐赠记录」（Row 33「在本系统众筹系统中的捐赠记录和捐物记录，每捐一次为一次记录」，V3 收尾批）。
 *
 * <p><b>只含众筹</b>：Row 33 原文限定「众筹系统中」；结对的捐款在结对中心（Row 34）、
 * 捐书与微心愿的物资各有自己的记录页（Row 38 / 35）。捐物点进去看物流轨迹走现成的运单详情。</p>
 *
 * <p>只依赖 mapper：本服务只读，依赖 service 容易绕出循环（捐款与众筹两个服务之间本来就刻意不互相注入）。</p>
 *
 * @author hengde
 */
@Service
public class DonateRecordService {

    public static final int KIND_ALL = 0;
    public static final int KIND_MONEY = 1;
    public static final int KIND_GOODS = 2;

    private DonateRecordMapper recordMapper;
    private DonateCrowdfundMapper crowdfundMapper;
    private DonateShipmentMapper shipmentMapper;
    private DonateItemMapper itemMapper;

    @Autowired
    public void setRecordMapper(DonateRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    @Autowired
    public void setCrowdfundMapper(DonateCrowdfundMapper crowdfundMapper) {
        this.crowdfundMapper = crowdfundMapper;
    }

    @Autowired
    public void setShipmentMapper(DonateShipmentMapper shipmentMapper) {
        this.shipmentMapper = shipmentMapper;
    }

    @Autowired
    public void setItemMapper(DonateItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    /** {@code kind}：0 全部 / 1 捐款 / 2 捐物。时间倒序，捐款按发起时间、捐物按寄出登记时间。 */
    public PageResult<DonationVOs.MyRecord> mine(Long volunteerId, Integer kind, PageQuery query) {
        int k = kind == null ? KIND_ALL : kind;
        if (k != KIND_ALL && k != KIND_MONEY && k != KIND_GOODS) {
            throw new BusinessException("kind 只能是 0全部 / 1捐款 / 2捐物");
        }
        long total = recordMapper.countMine(volunteerId, k, DonationFlow.BIZ_CROWDFUND, DonateFlow.BIZ_CROWDFUND_GOODS);
        long offset = (long) (query.getPage() - 1) * query.getSize();
        List<DonateRecordMapper.Row> rows = total <= offset ? List.of()
                : recordMapper.selectMine(volunteerId, k, DonationFlow.BIZ_CROWDFUND, DonateFlow.BIZ_CROWDFUND_GOODS,
                offset, query.getSize());

        Set<Long> shipmentIds = rows.stream().filter(r -> r.getKind() == KIND_GOODS)
                .map(DonateRecordMapper.Row::getRefId).collect(Collectors.toSet());
        Set<Long> goodsProjectIds = rows.stream().filter(r -> r.getKind() == KIND_GOODS)
                .map(DonateRecordMapper.Row::getProjectId).collect(Collectors.toSet());
        Map<Long, String> titles = new HashMap<>();
        Map<Long, DonateShipment> tracks = new HashMap<>();
        Map<Long, Integer> itemCounts = new HashMap<>();
        if (!shipmentIds.isEmpty()) {
            crowdfundMapper.selectList(Wrappers.<DonateCrowdfund>lambdaQuery()
                            .select(DonateCrowdfund::getId, DonateCrowdfund::getTitle)
                            .in(DonateCrowdfund::getId, goodsProjectIds))
                    .forEach(c -> titles.put(c.getId(), c.getTitle()));
            shipmentMapper.selectList(Wrappers.<DonateShipment>lambdaQuery()
                            .select(DonateShipment::getId, DonateShipment::getTrackLastContext,
                                    DonateShipment::getTrackLastTime)
                            .in(DonateShipment::getId, shipmentIds))
                    .forEach(s -> tracks.put(s.getId(), s));
            itemMapper.selectList(Wrappers.<DonateItem>lambdaQuery()
                            .select(DonateItem::getShipmentId)
                            .in(DonateItem::getShipmentId, shipmentIds))
                    .forEach(i -> itemCounts.merge(i.getShipmentId(), 1, Integer::sum));
        }
        List<DonationVOs.MyRecord> vos = rows.stream().map(r -> {
            DonationVOs.MyRecord vo = new DonationVOs.MyRecord();
            vo.setKind(r.getKind());
            vo.setRefId(r.getRefId());
            vo.setProjectId(r.getProjectId());
            vo.setStatus(r.getStatus());
            vo.setRecordTime(r.getRecordTime());
            if (r.getKind() == KIND_MONEY) {
                vo.setKindLabel("捐款");
                vo.setProjectTitle(r.getProjectTitle());
                vo.setAmount(r.getAmount());
                vo.setStatusLabel(DonationFlow.statusLabel(r.getStatus()));
            } else {
                vo.setKindLabel("捐物");
                vo.setProjectTitle(titles.get(r.getProjectId()));
                vo.setStatusLabel(DonateFlow.shipmentLabel(r.getStatus()));
                vo.setExpressCompany(r.getExpressCompany());
                vo.setExpressNo(r.getExpressNo());
                vo.setItemCount(itemCounts.getOrDefault(r.getRefId(), 0));
                DonateShipment s = tracks.get(r.getRefId());
                if (s != null) {
                    vo.setTrackLastContext(s.getTrackLastContext());
                    vo.setTrackLastTime(s.getTrackLastTime());
                }
            }
            return vo;
        }).toList();
        return PageResult.of(vos, total, query.getPage(), query.getSize());
    }
}
