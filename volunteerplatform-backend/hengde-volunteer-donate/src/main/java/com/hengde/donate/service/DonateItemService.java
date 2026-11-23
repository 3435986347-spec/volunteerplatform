package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerContactView;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.qrcode.BarcodeUtil;
import com.hengde.donate.constant.DonateCodes;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.dao.DonateBoxMapper;
import com.hengde.donate.dao.DonateItemMapper;
import com.hengde.donate.dao.DonateShipmentMapper;
import com.hengde.donate.dto.DonateItemQuery;
import com.hengde.donate.entity.DonateBox;
import com.hengde.donate.entity.DonateItem;
import com.hengde.donate.entity.DonateShipment;
import com.hengde.donate.entity.DonateWish;
import com.hengde.donate.vo.DonateFlowVOs;
import com.hengde.donate.vo.DonateItemExportRow;
import com.hengde.donate.vo.DonateItemRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 捐赠物资：专属码与标签（Row 17 第 7–8 步）、我的捐书记录（Row 38）、10 维搜索与导出（Row 17 F）。
 *
 * @author hengde
 */
@Slf4j
@Service
public class DonateItemService {

    /** 导出上限：再多就该缩小筛选范围——一次把几十万行读进内存写 Excel 会把应用拖垮。 */
    static final int EXPORT_LIMIT = 20_000;

    private static final int CODE_RETRY = 5;

    private DonateItemMapper itemMapper;
    private DonateShipmentMapper shipmentMapper;
    private DonateBoxMapper boxMapper;
    private DonateSourceTitleService sourceTitleService;
    private DonateTraceService traceService;
    private CryptoUtil cryptoUtil;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setItemMapper(DonateItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    @Autowired
    public void setShipmentMapper(DonateShipmentMapper shipmentMapper) {
        this.shipmentMapper = shipmentMapper;
    }

    @Autowired
    public void setBoxMapper(DonateBoxMapper boxMapper) {
        this.boxMapper = boxMapper;
    }

    @Autowired
    public void setSourceTitleService(DonateSourceTitleService sourceTitleService) {
        this.sourceTitleService = sourceTitleService;
    }

    @Autowired
    public void setCryptoUtil(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    @Autowired
    public void setTraceService(DonateTraceService traceService) {
        this.traceService = traceService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    /**
     * 生成物品专属码并返回标签内容（Row 17「给每一个捐赠物品生成一个专属条形码」「条形码面单上有该物品名称」）。
     *
     * <p><b>幂等</b>：已有码的直接返回原码（重印标签），不换新码——换了码，已经贴在书上的那张标签就作废了。
     * 生成是一条 CAS（{@code exclusive_code IS NULL AND status = 合格}），两台电脑同时点「生成」只会有一个码落库，
     * 另一个在重读时拿到同一个码。只有<b>核对合格</b>的物资才发码：不合格的要退回，不该带着我们的码离开。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public DonateFlowVOs.ItemLabel generateCode(Long itemId, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        DonateItem item = itemId == null ? null : itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException("物资不存在");
        }
        if (item.getExclusiveCode() != null) {
            return label(item);
        }
        for (int attempt = 0; attempt < CODE_RETRY; attempt++) {
            String code = DonateCodes.newItemCode();
            try {
                int rows = itemMapper.update(null, Wrappers.<DonateItem>lambdaUpdate()
                        .eq(DonateItem::getId, itemId)
                        .isNull(DonateItem::getExclusiveCode)
                        .eq(DonateItem::getStatus, DonateFlow.ITEM_QUALIFIED)
                        .set(DonateItem::getExclusiveCode, code)
                        .set(DonateItem::getUpdateTime, LocalDateTime.now()));
                if (rows == 1) {
                    traceService.record(item.getShipmentId(), itemId, null, DonateFlow.ACT_CODE,
                            "生成专属条码：" + item.getName(), DonateFlow.OP_ADMIN, adminId);
                    item.setExclusiveCode(code);
                    return label(item);
                }
                // CAS 输了：复核必须是当前读。本事务开头的 selectById 已把读视图定死在赢家提交之前，
                // 普通读会看到「还没有码、状态合格」，于是报出「只有合格的才能生成（当前：合格）」这种自相矛盾的话
                // ——并发用例 concurrentCodeGenerationYieldsOneCode 当场撞出来的
                DonateItem now = itemMapper.selectByIdForShare(itemId);
                if (now != null && now.getExclusiveCode() != null) {
                    return label(now);
                }
                throw new BusinessException("只有核对合格的物资才能生成专属码（当前："
                        + DonateFlow.itemLabel(now == null ? null : now.getStatus()) + "）");
            } catch (DuplicateKeyException e) {
                log.warn("物品专属码碰撞，换一个重试（第 {} 次）", attempt + 1);
            }
        }
        throw new BusinessException("专属码生成失败，请重试");
    }

    private DonateFlowVOs.ItemLabel label(DonateItem item) {
        DonateShipment s = shipmentMapper.selectById(item.getShipmentId());
        DonateFlowVOs.ItemLabel l = new DonateFlowVOs.ItemLabel();
        l.setItemId(item.getId());
        l.setExclusiveCode(item.getExclusiveCode());
        l.setBarcode(BarcodeUtil.toCode128PngDataUrl(item.getExclusiveCode(), 480, 120));
        l.setName(item.getName());
        l.setItemTypeLabel(DonateFlow.itemTypeLabel(item.getItemType()));
        l.setQuantity(item.getQuantity());
        l.setDonorName(s == null ? null : s.getDonorName());
        DonateSourceTitleService.Source src = DonateSourceTitleService.Source.of(item.getBizType(), item.getBizId());
        l.setCampaignTitle(sourceTitleService.titlesOf(List.of(src)).get(src));
        if (item.getBizType() != null && item.getBizType() == DonateFlow.BIZ_WISH) {
            // Row 12 G「面单上有：捐赠人名字、孩子编号、受捐学生、受捐单位」。标签只在后台生成与打印（donate:item），
            // 分拣的人要照着它把东西送到对的孩子手里，所以这里给明文
            DonateWish w = sourceTitleService.wishOfClaim(item.getBizId());
            if (w != null) {
                l.setWishNo(w.getWishNo());
                l.setChildName(StringUtils.hasText(w.getChildName()) ? cryptoUtil.decrypt(w.getChildName()) : null);
                l.setRecipientOrgName(w.getReportOrgName());
            }
        }
        return l;
    }

    /** 我的捐书记录（Row 38：编号、名称、编码、受捐学校、审核状态、物资类型、借阅次数）。取消的不列。 */
    public PageResult<DonateFlowVOs.Item> listMine(Long volunteerId, PageQuery query) {
        IPage<DonateItem> page = itemMapper.selectPage(query.toPage(), Wrappers.<DonateItem>lambdaQuery()
                .eq(DonateItem::getDonorVolunteerId, volunteerId)
                .ne(DonateItem::getStatus, DonateFlow.ITEM_CANCELLED)
                .orderByDesc(DonateItem::getId));
        Map<DonateSourceTitleService.Source, String> titles = sourceTitleService.titlesOf(page.getRecords().stream()
                .map(i -> DonateSourceTitleService.Source.of(i.getBizType(), i.getBizId())).collect(Collectors.toSet()));
        Set<Long> shipmentIds = page.getRecords().stream().map(DonateItem::getShipmentId).collect(Collectors.toSet());
        Map<Long, String> expressNos = shipmentIds.isEmpty() ? Map.of()
                : shipmentMapper.selectList(Wrappers.<DonateShipment>lambdaQuery()
                        .select(DonateShipment::getId, DonateShipment::getExpressNo)
                        .in(DonateShipment::getId, shipmentIds))
                .stream().collect(Collectors.toMap(DonateShipment::getId, DonateShipment::getExpressNo, (a, b) -> a));
        return PageResult.of(page.convert(i -> toItemVO(i,
                titles.get(DonateSourceTitleService.Source.of(i.getBizType(), i.getBizId())),
                expressNos.get(i.getShipmentId()), null)));
    }

    /** 10 维搜索（后台）。 */
    public PageResult<DonateItemRow> search(DonateItemQuery q, PageQuery page) {
        if (!normalize(q)) {
            return PageResult.of(page.toPage());
        }
        PageResult<DonateItemRow> result = PageResult.of(itemMapper.search(page.toPage(), q));
        fillMissingTitles(result.getRecords());
        result.getRecords().forEach(DonateItemService::decorate);
        return result;
    }

    /** 批量导出（每物资一行，Row 17 F）。超过上限直接报错让人缩小范围，<b>不静默截断</b>。 */
    public List<DonateItemExportRow> exportRows(DonateItemQuery q) {
        if (!normalize(q)) {
            return List.of();
        }
        List<DonateItemRow> rows = itemMapper.searchForExport(q, EXPORT_LIMIT + 1);
        if (rows.size() > EXPORT_LIMIT) {
            throw new BusinessException("结果超过 " + EXPORT_LIMIT + " 行，请按活动或进度缩小范围后分批导出");
        }
        fillMissingTitles(rows);
        Map<Long, VolunteerContactView> contacts = volunteerQueryService.listContactsByIds(
                rows.stream().map(DonateItemRow::getDonorVolunteerId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return rows.stream().map(r -> {
            VolunteerContactView c = contacts.get(r.getDonorVolunteerId());
            DonateItemExportRow e = new DonateItemExportRow();
            e.setCampaignTitle(r.getCampaignTitle());
            e.setBoxCode(r.getBoxCode());
            e.setExclusiveCode(r.getExclusiveCode());
            e.setCatalogBarcode(r.getCatalogBarcode());
            e.setDonorName(r.getDonorName());
            e.setDonorPhone(c == null ? null : c.phone());
            e.setDonorOrg(r.getDonorOrg());
            e.setVolunteerCodeUrl(c == null ? null : c.volunteerCodeUrl());
            e.setName(r.getName());
            e.setItemType(DonateFlow.itemTypeLabel(r.getItemType()));
            e.setQuantity(r.getQuantity());
            e.setExpressCompany(r.getExpressCompany());
            e.setExpressNo(r.getExpressNo());
            e.setProgress(DonateFlow.progressLabel(r.getStatus()));
            e.setRecipientOrgName(r.getRecipientOrgName());
            return e;
        }).toList();
    }

    /**
     * 规整搜索条件：码类大写去空白；<b>手机号换成志愿者 id</b>（密文不能 LIKE）。
     *
     * @return false = 手机号查无此人，结果必然为空，不必再查库
     */
    private boolean normalize(DonateItemQuery q) {
        q.setBoxCode(DonateCodes.normalize(emptyToNull(q.getBoxCode())));
        q.setExclusiveCode(DonateCodes.normalize(emptyToNull(q.getExclusiveCode())));
        q.setCatalogBarcode(trimToNull(q.getCatalogBarcode()));
        q.setExpressNo(q.getExpressNo() == null || q.getExpressNo().isBlank() ? null
                : DonateShipmentService.normalizeExpressNo(q.getExpressNo()));
        q.setDonorName(trimToNull(q.getDonorName()));
        q.setDonorOrg(trimToNull(q.getDonorOrg()));
        q.setItemName(trimToNull(q.getItemName()));
        q.setDonorVolunteerId(null);
        if (StringUtils.hasText(q.getDonorPhone())) {
            Long id = volunteerQueryService.findIdsByPhones(List.of(q.getDonorPhone().trim()))
                    .get(q.getDonorPhone().trim());
            if (id == null) {
                return false;
            }
            q.setDonorVolunteerId(id);
        }
        return true;
    }

    /**
     * 搜索 SQL 只 JOIN 了捐书活动表（{@code biz_type = 1}），微心愿物资的来源名在这里补：
     * 按类型分别去查，不能拿认领 id 去活动表对（撞上同号活动就显示成别人的活动名）。
     */
    private void fillMissingTitles(List<DonateItemRow> rows) {
        List<DonateItemRow> missing = rows.stream().filter(r -> r.getCampaignTitle() == null).toList();
        if (missing.isEmpty()) {
            return;
        }
        Map<DonateSourceTitleService.Source, String> titles = sourceTitleService.titlesOf(missing.stream()
                .map(r -> DonateSourceTitleService.Source.of(r.getBizType(), r.getBizId())).collect(Collectors.toSet()));
        missing.forEach(r -> r.setCampaignTitle(titles.get(DonateSourceTitleService.Source.of(r.getBizType(), r.getBizId()))));
    }

    private static void decorate(DonateItemRow r) {
        r.setItemTypeLabel(DonateFlow.itemTypeLabel(r.getItemType()));
        r.setStatusLabel(DonateFlow.progressLabel(r.getStatus()));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 实体 → 出参。箱码需要时由调用方传入（批量查过的）。 */
    static DonateFlowVOs.Item toItemVO(DonateItem i, String campaignTitle, String expressNo, String boxCode) {
        DonateFlowVOs.Item vo = new DonateFlowVOs.Item();
        vo.setId(i.getId());
        vo.setShipmentId(i.getShipmentId());
        vo.setName(i.getName());
        vo.setItemType(i.getItemType());
        vo.setItemTypeLabel(DonateFlow.itemTypeLabel(i.getItemType()));
        vo.setQuantity(i.getQuantity());
        vo.setCatalogBarcode(i.getCatalogBarcode());
        vo.setExclusiveCode(i.getExclusiveCode());
        vo.setStatus(i.getStatus());
        vo.setStatusLabel(DonateFlow.itemLabel(i.getStatus()));
        vo.setAuditLabel(DonateFlow.auditLabel(i.getStatus()));
        vo.setCheckRemark(i.getCheckRemark());
        vo.setBoxCode(boxCode);
        vo.setRecipientOrgName(i.getRecipientOrgName());
        vo.setDeliverTime(i.getDeliverTime());
        vo.setBorrowCount(i.getBorrowCount());
        vo.setCampaignTitle(campaignTitle);
        vo.setExpressNo(expressNo);
        return vo;
    }

    /** 给扫码识别用：按专属码找物资。 */
    public DonateFlowVOs.Item findByCode(String code) {
        DonateItem i = itemMapper.selectOne(Wrappers.<DonateItem>lambdaQuery().eq(DonateItem::getExclusiveCode, code));
        if (i == null) {
            return null;
        }
        String boxCode = null;
        if (i.getBoxId() != null) {
            DonateBox b = boxMapper.selectById(i.getBoxId());
            boxCode = b == null ? null : b.getBoxCode();
        }
        DonateSourceTitleService.Source src = DonateSourceTitleService.Source.of(i.getBizType(), i.getBizId());
        return toItemVO(i, sourceTitleService.titlesOf(List.of(src)).get(src), null, boxCode);
    }
}
