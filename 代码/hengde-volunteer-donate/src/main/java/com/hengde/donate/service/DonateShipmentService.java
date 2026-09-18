package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.constant.ExpressCompany;
import com.hengde.donate.constant.WishFlow;
import com.hengde.donate.dao.DonateItemMapper;
import com.hengde.donate.dao.DonateShipmentMapper;
import com.hengde.donate.dao.DonateWishClaimMapper;
import com.hengde.donate.dto.DonateItemInputDTO;
import com.hengde.donate.dto.ExpressDTO;
import com.hengde.donate.dto.ReturnAddressDTO;
import com.hengde.donate.dto.ShipmentCheckDTO;
import com.hengde.donate.dto.ShipmentRegisterDTO;
import com.hengde.donate.entity.DonateCampaign;
import com.hengde.donate.entity.DonateItem;
import com.hengde.donate.entity.DonateItemTrace;
import com.hengde.donate.entity.DonateShipment;
import com.hengde.donate.entity.DonateWishClaim;
import com.hengde.donate.vo.DonateFlowVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 捐赠运单：Row 17 第 1–6 步（报名 → 录入物资 → 登记快递 → 扫码到货 → 核对）与不合格退回。
 *
 * <p><b>每个扫码动作都是一条状态 CAS + 一条轨迹，同事务</b>（V3规划·捐书批）。
 * 状态迁移全部写成「WHERE 当前状态 = 期望状态」的条件更新、按影响行数判定，
 * 所以两台手机同时扫同一个包裹、双击、网络重投，都只会有一次生效，另一次拿到说得清的原因。</p>
 *
 * <p><b>核对与后台加物资的串行化</b>：两者都先以「当前读」的方式碰一下运单行（核对是状态 CAS，
 * 加物资是一条只改 update_time 的条件更新），于是一定一个在前一个在后——
 * 否则「核对刚提交完、一件新加的物资以『待核对』落了库」，那件东西就永远停在待核对上。</p>
 *
 * @author hengde
 */
@Service
public class DonateShipmentService {

    private DonateShipmentMapper shipmentMapper;
    private DonateItemMapper itemMapper;
    private BookCampaignService campaignService;
    private DonateTraceService traceService;
    private VolunteerQueryService volunteerQueryService;
    private CryptoUtil cryptoUtil;
    private DonateSourceTitleService sourceTitleService;
    private DonateWishClaimMapper wishClaimMapper;

    @Autowired
    public void setShipmentMapper(DonateShipmentMapper shipmentMapper) {
        this.shipmentMapper = shipmentMapper;
    }

    @Autowired
    public void setItemMapper(DonateItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    @Autowired
    public void setCampaignService(BookCampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @Autowired
    public void setTraceService(DonateTraceService traceService) {
        this.traceService = traceService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setCryptoUtil(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    @Autowired
    public void setSourceTitleService(DonateSourceTitleService sourceTitleService) {
        this.sourceTitleService = sourceTitleService;
    }

    @Autowired
    public void setWishClaimMapper(DonateWishClaimMapper wishClaimMapper) {
        this.wishClaimMapper = wishClaimMapper;
    }

    // ================= 捐赠人 =================

    /**
     * 报名并登记寄出（Row 17 第 1–4 步一次提交）。
     *
     * <p><b>须已实名</b>：物资流转全程要以捐赠人的名字作收件人与标签，游客没有实名信息；
     * 这也与微心愿「认领须注册通过志愿者」（Row 12 D）同一方向。</p>
     *
     * <p>「一个快递单号只登记一次」由生成列唯一键 {@code uk_active_express} 保证（见 V50），
     * 不靠先查再插。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public DonateFlowVOs.Shipment register(Long volunteerId, Long campaignId, ShipmentRegisterDTO dto) {
        if (volunteerId == null) {
            throw new BusinessException("志愿者不能为空");
        }
        if (!volunteerQueryService.filterActiveRegistered(List.of(volunteerId)).contains(volunteerId)) {
            throw new BusinessException("请先完成实名注册再参与捐书");
        }
        DonateCampaign campaign = campaignService.requireOpen(campaignId, LocalDateTime.now());
        return createShipment(DonateFlow.BIZ_BOOK_CAMPAIGN, campaign.getId(), volunteerId, dto);
    }

    /**
     * 物资流转的通用登记寄出：捐书活动、微心愿（及日后的众筹捐物）共用这一段，只是 {@code bizType/bizId} 不同。
     *
     * <p><b>调用方须在事务内调用</b>（本方法不自开事务），并先确认「这个人此刻有资格为这条业务寄送」——
     * 捐书是活动在报名时间内，微心愿是他持有这个心愿的有效认领（且已锁住那条认领）。</p>
     */
    public DonateFlowVOs.Shipment createShipment(int bizType, Long bizId, Long volunteerId, ShipmentRegisterDTO dto) {
        if (volunteerId == null) {
            throw new BusinessException("志愿者不能为空");
        }
        if (!volunteerQueryService.filterActiveRegistered(List.of(volunteerId)).contains(volunteerId)) {
            throw new BusinessException("请先完成实名注册再寄送物资");
        }
        LocalDateTime now = LocalDateTime.now();
        ExpressCompany company = requireCompany(dto.getExpressCode());
        String expressNo = normalizeExpressNo(dto.getExpressNo());
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            throw new BusinessException("请至少录入一件物资");
        }
        String donorName = volunteerQueryService.listNamesByIds(List.of(volunteerId)).get(volunteerId);

        DonateShipment s = new DonateShipment();
        s.setBizType(bizType);
        s.setBizId(bizId);
        s.setDonorVolunteerId(volunteerId);
        s.setDonorName(donorName == null ? "" : donorName);
        s.setDonorOrg(StringUtils.hasText(dto.getDonorOrg()) ? dto.getDonorOrg().trim() : null);
        s.setExpressCode(company.getCode());
        s.setExpressCompany(company.getLabel());
        s.setExpressNo(expressNo);
        s.setStatus(DonateFlow.SHIPMENT_SHIPPED);
        s.setShipTime(now);
        s.setReturnStatus(DonateFlow.RETURN_NONE);
        // 「其他」快递查不了轨迹：一登记就退出轮询集合，否则它会在每一轮都被捞出来白查一次
        s.setTrackDone(company.trackable() ? 0 : 1);
        try {
            shipmentMapper.insert(s);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("该快递单号已经登记过，请核对是否填错");
        }
        List<DonateItem> items = new ArrayList<>();
        for (DonateItemInputDTO in : dto.getItems()) {
            items.add(newItem(s, in, DonateFlow.ITEM_PENDING, null));
        }
        items.forEach(itemMapper::insert);
        traceService.record(s.getId(), null, null, DonateFlow.ACT_REGISTER,
                "登记寄出：" + company.getLabel() + " " + expressNo + "，共 " + items.size() + " 项物资",
                DonateFlow.OP_DONOR, volunteerId);
        return detail(s.getId(), true);
    }

    /**
     * 按来源批量取运单（含物资与轨迹），给微心愿中心这类「一条业务记录下挂几个包裹」的视图用。
     * 物资、轨迹各一次批量查，不按运单循环。
     */
    public Map<Long, List<DonateFlowVOs.Shipment>> listByBiz(int bizType, Collection<Long> bizIds) {
        if (bizIds == null || bizIds.isEmpty()) {
            return Map.of();
        }
        List<DonateFlowVOs.Shipment> vos = shipmentMapper.selectList(Wrappers.<DonateShipment>lambdaQuery()
                        .eq(DonateShipment::getBizType, bizType)
                        .in(DonateShipment::getBizId, bizIds)
                        .orderByAsc(DonateShipment::getId))
                .stream().map(s -> toVO(s, true)).collect(Collectors.toList());
        fill(vos);
        return vos.stream().collect(Collectors.groupingBy(DonateFlowVOs.Shipment::getBizId, LinkedHashMap::new,
                Collectors.toList()));
    }

    /**
     * 某条业务记录下「还活着」的物资数（在途 / 待核对 / 合格 / 已送达——没被判不合格、没退回、没取消的）。
     * 微心愿取消 / 撤销认领前用它：物资已经寄来并且可用，认领就不能再撤。
     *
     * <p><b>按物资判而不是按运单判</b>：一个包裹里的东西全被判不合格（等着退回），这个包裹对心愿已经没有用了，
     * 按运单数判会让认领人永远取消不了。<b>当前读 + 共享锁</b>；调用方须在事务内调用，
     * 且已先锁住那条业务记录（登记寄出也先锁它），否则「刚判完没有、对方就登记了一单」的窗口还在。</p>
     */
    public long countLiveGoods(int bizType, Long bizId) {
        return itemMapper.countLiveGoodsForShare(bizType, bizId, DonateFlow.ITEM_REJECTED, DonateFlow.ITEM_RETURNED,
                DonateFlow.ITEM_CANCELLED);
    }

    /** 某条业务记录下还没到货或还没核对的运单数（当前读 + 共享锁，理由见 mapper 注释）。 */
    public long countUnsettledShipments(int bizType, Long bizId) {
        return shipmentMapper.countUnsettledForShare(bizType, bizId, DonateFlow.SHIPMENT_SHIPPED,
                DonateFlow.SHIPMENT_ARRIVED);
    }

    /** 我的运单（Row 38「运单管理：展示 TA 寄出的有哪些快递，快递里面的物资明细」）。 */
    public PageResult<DonateFlowVOs.Shipment> listMine(Long volunteerId, PageQuery query) {
        IPage<DonateShipment> page = shipmentMapper.selectPage(query.toPage(), Wrappers.<DonateShipment>lambdaQuery()
                .eq(DonateShipment::getDonorVolunteerId, volunteerId)
                .orderByDesc(DonateShipment::getId));
        return toPage(page, false);
    }

    /** 我的运单详情。不是本人的与不存在返回同一句话，防按 id 枚举。 */
    public DonateFlowVOs.Shipment detailMine(Long shipmentId, Long volunteerId) {
        requireOwn(shipmentId, volunteerId);
        return detail(shipmentId, true);
    }

    /** 取消（仅到货之前）：运单 CAS 已寄出 → 已取消，物资待到货 → 已取消，释放快递单号。 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long shipmentId, Long volunteerId) {
        requireOwn(shipmentId, volunteerId);
        int rows = shipmentMapper.update(null, Wrappers.<DonateShipment>lambdaUpdate()
                .eq(DonateShipment::getId, shipmentId)
                .eq(DonateShipment::getDonorVolunteerId, volunteerId)
                .eq(DonateShipment::getStatus, DonateFlow.SHIPMENT_SHIPPED)
                .set(DonateShipment::getStatus, DonateFlow.SHIPMENT_CANCELLED)
                .set(DonateShipment::getTrackDone, 1)
                .set(DonateShipment::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("包裹已被机构签收，不能再取消");
        }
        itemMapper.update(null, Wrappers.<DonateItem>lambdaUpdate()
                .eq(DonateItem::getShipmentId, shipmentId)
                .eq(DonateItem::getStatus, DonateFlow.ITEM_PENDING)
                .set(DonateItem::getStatus, DonateFlow.ITEM_CANCELLED)
                .set(DonateItem::getUpdateTime, LocalDateTime.now()));
        traceService.record(shipmentId, null, null, DonateFlow.ACT_CANCEL, "捐赠人取消了这次寄送",
                DonateFlow.OP_DONOR, volunteerId);
    }

    /**
     * 提交不合格物资的退回收件信息（Row 17 D「退回的话需要志愿者提交收件信息」）。
     * 寄回之前可以改（待提交 / 待寄回两态都接受），寄回之后就不能再改了。电话密文存储。
     */
    @Transactional(rollbackFor = Exception.class)
    public void submitReturnAddress(Long shipmentId, Long volunteerId, ReturnAddressDTO dto) {
        requireOwn(shipmentId, volunteerId);
        int rows = shipmentMapper.update(null, Wrappers.<DonateShipment>lambdaUpdate()
                .eq(DonateShipment::getId, shipmentId)
                .eq(DonateShipment::getDonorVolunteerId, volunteerId)
                .in(DonateShipment::getReturnStatus, DonateFlow.RETURN_AWAIT_ADDRESS, DonateFlow.RETURN_AWAIT_SHIP)
                .set(DonateShipment::getReturnStatus, DonateFlow.RETURN_AWAIT_SHIP)
                .set(DonateShipment::getReturnName, dto.getName().trim())
                .set(DonateShipment::getReturnPhone, cryptoUtil.encrypt(dto.getPhone().trim()))
                .set(DonateShipment::getReturnAddress, dto.getAddress().trim())
                .set(DonateShipment::getReturnSubmitTime, LocalDateTime.now())
                .set(DonateShipment::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("这个包裹没有需要退回的物资，或已经寄回");
        }
        traceService.record(shipmentId, null, null, DonateFlow.ACT_RETURN_ADDRESS, "捐赠人提交了退回收件信息",
                DonateFlow.OP_DONOR, volunteerId);
    }

    // ================= 管理端 =================

    /** 运单列表（按活动 / 状态 / 退回状态 / 快递单号 / 捐赠人名字筛）。 */
    public PageResult<DonateFlowVOs.Shipment> listForAdmin(PageQuery query, Long campaignId, Integer status,
                                                            Integer returnStatus, String expressNo, String donorName) {
        return listForAdmin(query, DonateFlow.BIZ_BOOK_CAMPAIGN, campaignId, status, returnStatus, expressNo, donorName);
    }

    /**
     * 运单列表，按来源类型筛（捐款批起众筹捐物也在这里管）。{@code bizType} 不传＝捐书活动，保持原来的行为；
     * {@code bizId} 按类型分别是捐书活动 id / 认领 id / 众筹项目 id。
     */
    public PageResult<DonateFlowVOs.Shipment> listForAdmin(PageQuery query, Integer bizType, Long bizId, Integer status,
                                                            Integer returnStatus, String expressNo, String donorName) {
        int type = bizType == null ? DonateFlow.BIZ_BOOK_CAMPAIGN : bizType;
        if (type != DonateFlow.BIZ_BOOK_CAMPAIGN && type != DonateFlow.BIZ_WISH && type != DonateFlow.BIZ_CROWDFUND_GOODS) {
            throw new BusinessException("bizType 只能是 1捐书活动 / 2微心愿 / 3众筹捐物");
        }
        IPage<DonateShipment> page = shipmentMapper.selectPage(query.toPage(), Wrappers.<DonateShipment>lambdaQuery()
                .eq(DonateShipment::getBizType, type)
                .eq(bizId != null, DonateShipment::getBizId, bizId)
                .eq(status != null, DonateShipment::getStatus, status)
                .eq(returnStatus != null, DonateShipment::getReturnStatus, returnStatus)
                .eq(StringUtils.hasText(expressNo), DonateShipment::getExpressNo,
                        expressNo == null ? null : normalizeExpressNo(expressNo))
                .like(StringUtils.hasText(donorName), DonateShipment::getDonorName, donorName)
                .orderByDesc(DonateShipment::getId));
        return toPage(page, true);
    }

    public DonateFlowVOs.Shipment detailForAdmin(Long shipmentId) {
        if (shipmentId == null || shipmentMapper.selectById(shipmentId) == null) {
            throw new BusinessException("运单不存在");
        }
        DonateFlowVOs.Shipment vo = detail(shipmentId, true);
        return vo;
    }

    /**
     * 扫码确认到货（Row 17 第 5 步）。运单 CAS 已寄出 → 已到货，物资待到货 → 已到货待核对，
     * 同时把它移出物流轮询集合（我们已经收到了，不必再问快递100）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void arrive(Long shipmentId, Long adminId) {
        requireAdmin(adminId);
        LocalDateTime now = LocalDateTime.now();
        int rows = shipmentMapper.update(null, Wrappers.<DonateShipment>lambdaUpdate()
                .eq(DonateShipment::getId, shipmentId)
                .eq(DonateShipment::getStatus, DonateFlow.SHIPMENT_SHIPPED)
                .set(DonateShipment::getStatus, DonateFlow.SHIPMENT_ARRIVED)
                .set(DonateShipment::getArriveTime, now)
                .set(DonateShipment::getArriveBy, adminId)
                .set(DonateShipment::getTrackDone, 1)
                .set(DonateShipment::getUpdateTime, now));
        if (rows != 1) {
            DonateShipment s = shipmentId == null ? null : shipmentMapper.selectById(shipmentId);
            if (s == null) {
                throw new BusinessException("运单不存在");
            }
            if (s.getStatus() != null && s.getStatus() == DonateFlow.SHIPMENT_CANCELLED) {
                throw new BusinessException("捐赠人已取消这次寄送");
            }
            throw new BusinessException("该包裹已于 " + s.getArriveTime() + " 确认到货");
        }
        itemMapper.update(null, Wrappers.<DonateItem>lambdaUpdate()
                .eq(DonateItem::getShipmentId, shipmentId)
                .eq(DonateItem::getStatus, DonateFlow.ITEM_PENDING)
                .set(DonateItem::getStatus, DonateFlow.ITEM_ARRIVED)
                .set(DonateItem::getUpdateTime, now));
        traceService.record(shipmentId, null, null, DonateFlow.ACT_ARRIVE, "机构已收到包裹",
                DonateFlow.OP_ADMIN, adminId);
    }

    /**
     * 核对捐赠单据（Row 17 第 6 步）。
     *
     * <p><b>先 CAS 运单再读物资</b>：状态 CAS 是一条当前读的 UPDATE，它把本事务排在所有并发的「后台加物资」之后或之前；
     * 之后再读「这个包裹里有哪些待核对物资」，读到的就是完整集合。结果必须<b>恰好覆盖</b>这个集合——
     * 漏判一件会让它永远停在「已到货待核对」，多判一件（别的包裹的物资）说明前端传错了。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void check(Long shipmentId, ShipmentCheckDTO dto, Long adminId) {
        requireAdmin(adminId);
        if (dto == null || dto.getResults() == null || dto.getResults().isEmpty()) {
            throw new BusinessException("核对结果不能为空");
        }
        Map<Long, ShipmentCheckDTO.ItemResult> byItem = new HashMap<>();
        boolean anyRejected = false;
        for (ShipmentCheckDTO.ItemResult r : dto.getResults()) {
            if (byItem.put(r.getItemId(), r) != null) {
                throw new BusinessException("同一件物资不能判两次：" + r.getItemId());
            }
            if (!Boolean.TRUE.equals(r.getQualified())) {
                anyRejected = true;
                if (!StringUtils.hasText(r.getRemark())) {
                    throw new BusinessException("不合格的物资须写明原因——捐赠人要据此决定是否寄回");
                }
            }
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = shipmentMapper.update(null, Wrappers.<DonateShipment>lambdaUpdate()
                .eq(DonateShipment::getId, shipmentId)
                .eq(DonateShipment::getStatus, DonateFlow.SHIPMENT_ARRIVED)
                .set(DonateShipment::getStatus, DonateFlow.SHIPMENT_CHECKED)
                .set(DonateShipment::getCheckTime, now)
                .set(DonateShipment::getCheckBy, adminId)
                .set(DonateShipment::getReturnStatus, anyRejected ? DonateFlow.RETURN_AWAIT_ADDRESS : DonateFlow.RETURN_NONE)
                .set(DonateShipment::getUpdateTime, now));
        if (rows != 1) {
            throw new BusinessException("该包裹不在待核对状态（须先确认到货，且只能核对一次）");
        }
        List<DonateItem> pending = itemMapper.selectList(Wrappers.<DonateItem>lambdaQuery()
                .eq(DonateItem::getShipmentId, shipmentId)
                .eq(DonateItem::getStatus, DonateFlow.ITEM_ARRIVED));
        Set<Long> expected = pending.stream().map(DonateItem::getId).collect(Collectors.toSet());
        if (!expected.equals(byItem.keySet())) {
            Set<Long> missing = new HashSet<>(expected);
            missing.removeAll(byItem.keySet());
            Set<Long> extra = new HashSet<>(byItem.keySet());
            extra.removeAll(expected);
            throw new BusinessException("核对结果须恰好覆盖该包裹的全部待核对物资"
                    + (missing.isEmpty() ? "" : "；漏判：" + missing) + (extra.isEmpty() ? "" : "；不属于本包裹或已核对：" + extra));
        }
        for (DonateItem item : pending) {
            ShipmentCheckDTO.ItemResult r = byItem.get(item.getId());
            boolean ok = Boolean.TRUE.equals(r.getQualified());
            int updated = itemMapper.update(null, Wrappers.<DonateItem>lambdaUpdate()
                    .eq(DonateItem::getId, item.getId())
                    .eq(DonateItem::getStatus, DonateFlow.ITEM_ARRIVED)
                    .set(DonateItem::getStatus, ok ? DonateFlow.ITEM_QUALIFIED : DonateFlow.ITEM_REJECTED)
                    .set(DonateItem::getCheckRemark, StringUtils.hasText(r.getRemark()) ? r.getRemark().trim() : null)
                    .set(DonateItem::getUpdateTime, now));
            if (updated != 1) {
                throw new BusinessException("物资 " + item.getId() + " 状态已变化，请刷新后重试");
            }
            traceService.record(shipmentId, item.getId(), null, ok ? DonateFlow.ACT_CHECK_PASS : DonateFlow.ACT_CHECK_FAIL,
                    ok ? "核对合格：" + item.getName() : "核对不合格：" + item.getName() + "（" + r.getRemark().trim() + "）",
                    DonateFlow.OP_ADMIN, adminId);
        }
    }

    /**
     * 登记退回寄出（Row 17 D「我们这边会给他上传快递单号」）：退回状态 CAS 待寄回 → 已寄回，
     * 不合格物资 → 已退回。<b>按运单退，不按物资退</b>——不合格的几件本来就是装在一个包裹里寄回去的。
     */
    @Transactional(rollbackFor = Exception.class)
    public void returnShip(Long shipmentId, ExpressDTO dto, Long adminId) {
        requireAdmin(adminId);
        ExpressCompany company = requireCompany(dto.getExpressCode());
        String no = normalizeExpressNo(dto.getExpressNo());
        LocalDateTime now = LocalDateTime.now();
        int rows = shipmentMapper.update(null, Wrappers.<DonateShipment>lambdaUpdate()
                .eq(DonateShipment::getId, shipmentId)
                .eq(DonateShipment::getReturnStatus, DonateFlow.RETURN_AWAIT_SHIP)
                .set(DonateShipment::getReturnStatus, DonateFlow.RETURN_SHIPPED)
                .set(DonateShipment::getReturnExpressCode, company.getCode())
                .set(DonateShipment::getReturnExpressCompany, company.getLabel())
                .set(DonateShipment::getReturnExpressNo, no)
                .set(DonateShipment::getReturnTime, now)
                .set(DonateShipment::getReturnBy, adminId)
                .set(DonateShipment::getUpdateTime, now));
        if (rows != 1) {
            DonateShipment s = shipmentId == null ? null : shipmentMapper.selectById(shipmentId);
            if (s == null) {
                throw new BusinessException("运单不存在");
            }
            throw new BusinessException("该包裹当前不能登记寄回（" + DonateFlow.returnLabel(s.getReturnStatus()) + "）");
        }
        itemMapper.update(null, Wrappers.<DonateItem>lambdaUpdate()
                .eq(DonateItem::getShipmentId, shipmentId)
                .eq(DonateItem::getStatus, DonateFlow.ITEM_REJECTED)
                .set(DonateItem::getStatus, DonateFlow.ITEM_RETURNED)
                .set(DonateItem::getUpdateTime, now));
        traceService.record(shipmentId, null, null, DonateFlow.ACT_RETURN_SHIP,
                "不合格物资已寄回：" + company.getLabel() + " " + no, DonateFlow.OP_ADMIN, adminId);
    }

    /**
     * 后台单独添加物资（Row 17「后台也可单独添加或减少物品」）：只在「已到货 / 已核对」的包裹上加——
     * 那是在拆包时才发现单子上漏写了。已到货的加成「待核对」，已核对的直接算「合格」（后台亲手核过）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long addItem(Long shipmentId, DonateItemInputDTO dto, Long adminId) {
        requireAdmin(adminId);
        lockWishClaimIfAny(shipmentId);
        // 先以当前读碰一下运单行：与并发的核对 CAS 串行化（见类注释）
        int touched = shipmentMapper.update(null, Wrappers.<DonateShipment>lambdaUpdate()
                .eq(DonateShipment::getId, shipmentId)
                .in(DonateShipment::getStatus, DonateFlow.SHIPMENT_ARRIVED, DonateFlow.SHIPMENT_CHECKED)
                .set(DonateShipment::getUpdateTime, LocalDateTime.now()));
        if (touched != 1) {
            throw new BusinessException("只能给已到货的包裹添加物资");
        }
        // 运单头必须当前读：lockWishClaimIfAny 的普通读已把读视图定在碰行之前，再用普通读会看到「已到货」
        // 而实际已被并发核对成「已核对」——新物资就会以「待核对」落进一个已核对的包裹，永远没人再核它
        DonateShipment s = shipmentMapper.selectHeadForUpdate(shipmentId);
        int status = s.getStatus() == DonateFlow.SHIPMENT_CHECKED ? DonateFlow.ITEM_QUALIFIED : DonateFlow.ITEM_ARRIVED;
        DonateItem item = newItem(s, dto, status, adminId);
        itemMapper.insert(item);
        traceService.record(shipmentId, item.getId(), null, DonateFlow.ACT_ADMIN_ADD, "机构补录物资：" + item.getName(),
                DonateFlow.OP_ADMIN, adminId);
        return item.getId();
    }

    /**
     * 后台单独减少物资（逻辑删除）。<b>装了箱、送达了、已寄回的不能删</b>——那等于抹掉一件真实发生过的流转；
     * 条件写进删除语句的 WHERE。删掉的若是最后一件不合格物资，退回流程也随之撤销。
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeItem(Long itemId, Long adminId) {
        requireAdmin(adminId);
        DonateItem item = itemId == null ? null : itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException("物资不存在");
        }
        int rows = itemMapper.delete(Wrappers.<DonateItem>lambdaQuery()
                .eq(DonateItem::getId, itemId)
                .isNull(DonateItem::getBoxId)
                .in(DonateItem::getStatus, DonateFlow.ITEM_PENDING, DonateFlow.ITEM_ARRIVED,
                        DonateFlow.ITEM_QUALIFIED, DonateFlow.ITEM_REJECTED));
        if (rows != 1) {
            throw new BusinessException("该物资已装箱 / 已送达 / 已寄回，不能删除（当前："
                    + DonateFlow.itemLabel(item.getStatus()) + "）");
        }
        if (item.getStatus() == DonateFlow.ITEM_REJECTED) {
            Long stillRejected = itemMapper.selectCount(Wrappers.<DonateItem>lambdaQuery()
                    .eq(DonateItem::getShipmentId, item.getShipmentId())
                    .eq(DonateItem::getStatus, DonateFlow.ITEM_REJECTED));
            if (stillRejected == 0) {
                shipmentMapper.update(null, Wrappers.<DonateShipment>lambdaUpdate()
                        .eq(DonateShipment::getId, item.getShipmentId())
                        .in(DonateShipment::getReturnStatus, DonateFlow.RETURN_AWAIT_ADDRESS, DonateFlow.RETURN_AWAIT_SHIP)
                        .set(DonateShipment::getReturnStatus, DonateFlow.RETURN_NONE)
                        .set(DonateShipment::getUpdateTime, LocalDateTime.now()));
            }
        }
        traceService.record(item.getShipmentId(), itemId, null, DonateFlow.ACT_ADMIN_REMOVE,
                "机构移除了物资：" + item.getName(), DonateFlow.OP_ADMIN, adminId);
    }

    // ================= 内部 =================

    /**
     * 微心愿的包裹补录物资前，先以当前读（共享锁）锁住那条认领：与「实现心愿」同一个加锁顺序（认领 → 运单），
     * 反过来会与它互等成死锁。认领已经结束（取消 / 撤销 / 已实现）的不能再补——补进来的东西没有下一步可走，
     * 会永远停在「合格」上。捐书的包裹直接放行。
     */
    private void lockWishClaimIfAny(Long shipmentId) {
        DonateShipment peek = shipmentId == null ? null : shipmentMapper.selectById(shipmentId);
        if (peek == null || peek.getBizType() == null || peek.getBizType() != DonateFlow.BIZ_WISH) {
            return;
        }
        DonateWishClaim claim = wishClaimMapper.selectByIdForShare(peek.getBizId());
        if (claim == null || claim.getStatus() == null || claim.getStatus() != WishFlow.CLAIM_ACTIVE) {
            throw new BusinessException("这个心愿的认领已经结束（取消 / 撤销 / 已实现），不能再补录物资");
        }
    }

    private DonateShipment requireOwn(Long shipmentId, Long volunteerId) {
        DonateShipment s = shipmentId == null ? null : shipmentMapper.selectById(shipmentId);
        if (s == null || !s.getDonorVolunteerId().equals(volunteerId)) {
            throw new BusinessException("运单不存在");
        }
        return s;
    }

    private static void requireAdmin(Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
    }

    static ExpressCompany requireCompany(String code) {
        return ExpressCompany.ofCode(code).orElseThrow(() -> new BusinessException("请从列表中选择快递公司"));
    }

    /** 单号去空白、转大写：扫码枪与手输的差别不该让同一个单号登记两次。 */
    static String normalizeExpressNo(String raw) {
        String no = raw == null ? "" : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (no.isEmpty()) {
            throw new BusinessException("请填写快递单号");
        }
        if (no.length() > 64) {
            throw new BusinessException("快递单号过长");
        }
        return no;
    }

    private static DonateItem newItem(DonateShipment s, DonateItemInputDTO in, int status, Long addedBy) {
        if (in == null || !StringUtils.hasText(in.getName())) {
            throw new BusinessException("请填写物资名称");
        }
        if (!DonateFlow.isValidItemType(in.getItemType())) {
            throw new BusinessException("物资类型只能是 1 课外书籍 / 2 学习用品 / 3 运动器材 / 9 其他");
        }
        int qty = in.getQuantity() == null ? 1 : in.getQuantity();
        if (qty < 1 || qty > 9999) {
            throw new BusinessException("物资数量须在 1 到 9999 之间");
        }
        DonateItem item = new DonateItem();
        item.setShipmentId(s.getId());
        item.setBizType(s.getBizType());
        item.setBizId(s.getBizId());
        item.setDonorVolunteerId(s.getDonorVolunteerId());
        item.setName(in.getName().trim());
        item.setItemType(in.getItemType());
        item.setQuantity(qty);
        item.setCatalogBarcode(StringUtils.hasText(in.getCatalogBarcode()) ? in.getCatalogBarcode().trim() : null);
        item.setStatus(status);
        item.setBorrowCount(0);
        item.setAddedBy(addedBy);
        return item;
    }

    private DonateFlowVOs.Shipment detail(Long shipmentId, boolean withReturnPhone) {
        DonateShipment s = shipmentMapper.selectById(shipmentId);
        DonateFlowVOs.Shipment vo = toVO(s, withReturnPhone);
        fill(List.of(vo));
        return vo;
    }

    private PageResult<DonateFlowVOs.Shipment> toPage(IPage<DonateShipment> page, boolean forAdmin) {
        PageResult<DonateFlowVOs.Shipment> result = PageResult.of(page.convert(s -> {
            DonateFlowVOs.Shipment vo = toVO(s, forAdmin);
            if (!forAdmin) {
                vo.setDonorVolunteerId(null);
            }
            return vo;
        }));
        fill(result.getRecords());
        return result;
    }

    private DonateFlowVOs.Shipment toVO(DonateShipment s, boolean withReturnPhone) {
        DonateFlowVOs.Shipment vo = new DonateFlowVOs.Shipment();
        vo.setId(s.getId());
        vo.setBizType(s.getBizType());
        vo.setBizId(s.getBizId());
        vo.setDonorName(s.getDonorName());
        vo.setDonorOrg(s.getDonorOrg());
        vo.setExpressCode(s.getExpressCode());
        vo.setExpressCompany(s.getExpressCompany());
        vo.setExpressNo(s.getExpressNo());
        vo.setStatus(s.getStatus());
        vo.setStatusLabel(DonateFlow.shipmentLabel(s.getStatus()));
        vo.setShipTime(s.getShipTime());
        vo.setArriveTime(s.getArriveTime());
        vo.setCheckTime(s.getCheckTime());
        vo.setReturnStatus(s.getReturnStatus());
        vo.setReturnStatusLabel(DonateFlow.returnLabel(s.getReturnStatus()));
        vo.setReturnName(s.getReturnName());
        if (withReturnPhone && StringUtils.hasText(s.getReturnPhone())) {
            vo.setReturnPhone(cryptoUtil.decrypt(s.getReturnPhone()));
        }
        vo.setReturnAddress(s.getReturnAddress());
        vo.setReturnExpressCompany(s.getReturnExpressCompany());
        vo.setReturnExpressNo(s.getReturnExpressNo());
        vo.setReturnTime(s.getReturnTime());
        vo.setTrackState(s.getTrackState());
        vo.setTrackStateLabel(DonateTrackService.stateLabel(s.getTrackState()));
        vo.setTrackLastContext(s.getTrackLastContext());
        vo.setTrackLastTime(s.getTrackLastTime());
        vo.setSubscribeStatus(s.getSubscribeStatus());
        vo.setSubscribeStatusLabel(DonateFlow.subscribeLabel(s.getSubscribeStatus()));
        vo.setSubscribeError(s.getSubscribeError());
        vo.setDonorVolunteerId(s.getDonorVolunteerId());
        return vo;
    }

    /** 物资、轨迹、活动名各一次批量查，不按运单循环。 */
    private void fill(List<DonateFlowVOs.Shipment> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> ids = records.stream().map(DonateFlowVOs.Shipment::getId).toList();
        Map<Long, List<DonateItem>> items = itemMapper.selectList(Wrappers.<DonateItem>lambdaQuery()
                        .in(DonateItem::getShipmentId, ids).orderByAsc(DonateItem::getId))
                .stream().collect(Collectors.groupingBy(DonateItem::getShipmentId));
        Map<Long, List<DonateItemTrace>> traces = traceService.listByShipments(ids);
        Map<DonateSourceTitleService.Source, String> titles = sourceTitleService.titlesOf(records.stream()
                .map(r -> DonateSourceTitleService.Source.of(r.getBizType(), r.getBizId())).collect(Collectors.toSet()));
        for (DonateFlowVOs.Shipment vo : records) {
            vo.setCampaignTitle(titles.get(DonateSourceTitleService.Source.of(vo.getBizType(), vo.getBizId())));
            for (DonateItem i : items.getOrDefault(vo.getId(), List.of())) {
                vo.getItems().add(DonateItemService.toItemVO(i, null, null, null));
            }
            for (DonateItemTrace t : traces.getOrDefault(vo.getId(), List.of())) {
                DonateFlowVOs.Trace tv = new DonateFlowVOs.Trace();
                tv.setTime(t.getCreateTime());
                tv.setAction(t.getAction());
                tv.setContent(t.getContent());
                tv.setItemId(t.getItemId());
                vo.getTraces().add(tv);
            }
        }
    }

    /** 给扫码识别用：按快递单号找运单（可能多条——不同快递公司的单号理论上可重）。 */
    public List<DonateFlowVOs.Shipment> findByExpressNo(String expressNo) {
        String no = normalizeExpressNo(expressNo);
        List<DonateShipment> list = shipmentMapper.selectList(Wrappers.<DonateShipment>lambdaQuery()
                .eq(DonateShipment::getExpressNo, no)
                .orderByDesc(DonateShipment::getId)
                .last("LIMIT 10"));
        List<DonateFlowVOs.Shipment> vos = list.stream().map(s -> toVO(s, false)).collect(Collectors.toList());
        fill(vos);
        return vos;
    }

    Collection<Long> idsOf(List<DonateFlowVOs.Shipment> list) {
        return list.stream().map(DonateFlowVOs.Shipment::getId).toList();
    }
}
