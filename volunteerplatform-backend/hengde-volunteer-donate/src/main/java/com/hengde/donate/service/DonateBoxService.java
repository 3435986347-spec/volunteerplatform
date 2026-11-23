package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.qrcode.BarcodeUtil;
import com.hengde.donate.constant.DonateCodes;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.dao.DonateBoxMapper;
import com.hengde.donate.dao.DonateItemMapper;
import com.hengde.donate.entity.DonateBox;
import com.hengde.donate.entity.DonateCampaign;
import com.hengde.donate.entity.DonateItem;
import com.hengde.donate.entity.DonateRecipientOrg;
import com.hengde.donate.vo.DonateFlowVOs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 装箱与送达（Row 17 第 9–10 步：「生成箱子条形码，把物品扫码装入箱子」「扫码确认箱子以及里面的物品
 * 到了哪个受赠单位」）。
 *
 * <p><b>装箱是一次扫一件</b>（一个扫码动作 = 一次请求），不做批量表单——前端将来是手机网页（Row 17 F），
 * 设计成批量会让那一侧重来（url 文档 V3 捐书批注记）。</p>
 *
 * <p><b>加锁顺序一律「先箱后物」</b>——压测撞出来的一条纪律。装箱 / 出箱：先对箱子取 S（只读比对），
 * 再对那一件物资做单表 CAS；送达：先对箱子取 X（CAS），再按主键逐件改箱内物资。
 * 两边顺序一致就不成环；早先装箱的 {@code UPDATE ... JOIN} 实际是「先物后箱」，
 * 与送达互等，{@code DonateBoxConcurrencyTest} 稳定撞出 {@code ER_LOCK_DEADLOCK}。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class DonateBoxService {

    private static final int CODE_RETRY = 5;

    private DonateBoxMapper boxMapper;
    private DonateItemMapper itemMapper;
    private BookCampaignService campaignService;
    private DonateMasterDataService masterDataService;
    private DonateTraceService traceService;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setBoxMapper(DonateBoxMapper boxMapper) {
        this.boxMapper = boxMapper;
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
    public void setMasterDataService(DonateMasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    @Autowired
    public void setTraceService(DonateTraceService traceService) {
        this.traceService = traceService;
    }

    @Autowired
    public void setTransactionTemplate(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    /** 建一只箱子（生成箱码）。草稿活动不能建箱——那时不可能收到任何东西。 */
    public DonateFlowVOs.Box create(Long campaignId, Long adminId) {
        requireAdmin(adminId);
        DonateCampaign c = campaignService.require(campaignId);
        if (c.getStatus() == null || c.getStatus() == DonateFlow.CAMPAIGN_DRAFT) {
            throw new BusinessException("活动尚未发布，不能建箱");
        }
        for (int attempt = 0; attempt < CODE_RETRY; attempt++) {
            DonateBox box = new DonateBox();
            box.setBoxCode(DonateCodes.newBoxCode());
            box.setBizType(DonateFlow.BIZ_BOOK_CAMPAIGN);
            box.setBizId(campaignId);
            box.setStatus(DonateFlow.BOX_PACKING);
            box.setCreateBy(adminId);
            try {
                boxMapper.insert(box);
                return detail(box.getId());
            } catch (DuplicateKeyException e) {
                log.warn("箱码碰撞，换一个重试（第 {} 次）", attempt + 1);
            }
        }
        throw new BusinessException("箱码生成失败，请重试");
    }

    public PageResult<DonateFlowVOs.Box> list(PageQuery query, Long campaignId, Integer status) {
        IPage<DonateBox> page = boxMapper.selectPage(query.toPage(), Wrappers.<DonateBox>lambdaQuery()
                .eq(campaignId != null, DonateBox::getBizId, campaignId)
                .eq(status != null, DonateBox::getStatus, status)
                .orderByDesc(DonateBox::getId));
        Map<Long, Long> counts = countItems(page.getRecords().stream().map(DonateBox::getId).toList());
        Map<Long, String> titles = campaignService.titlesOf(page.getRecords().stream()
                .map(DonateBox::getBizId).collect(Collectors.toSet()));
        return PageResult.of(page.convert(b -> {
            DonateFlowVOs.Box vo = toVO(b, titles.get(b.getBizId()));
            vo.setItemCount(counts.getOrDefault(b.getId(), 0L).intValue());
            return vo;
        }));
    }

    /** 箱子详情：带条码图（重印箱码）与箱内物资。 */
    public DonateFlowVOs.Box detail(Long boxId) {
        DonateBox b = boxId == null ? null : boxMapper.selectById(boxId);
        if (b == null) {
            throw new BusinessException("箱子不存在");
        }
        DonateFlowVOs.Box vo = toVO(b, campaignService.titlesOf(List.of(b.getBizId())).get(b.getBizId()));
        vo.setBarcode(BarcodeUtil.toCode128PngDataUrl(b.getBoxCode(), 480, 120));
        List<DonateItem> items = itemMapper.selectList(Wrappers.<DonateItem>lambdaQuery()
                .eq(DonateItem::getBoxId, boxId).orderByAsc(DonateItem::getId));
        items.forEach(i -> vo.getItems().add(DonateItemService.toItemVO(i, null, null, b.getBoxCode())));
        vo.setItemCount(items.size());
        return vo;
    }

    /**
     * 扫物品专属码装箱：<b>先对箱子取 S 并确认仍在装箱中，再对物资做单表 CAS</b>（先箱后物，见类注释）。
     * 影响行数为 0 时补一次查询说清原因——「装不进去」有五种，一律报「失败」柜台上的人不知道该怎么办。
     */
    @Transactional(rollbackFor = Exception.class)
    public DonateFlowVOs.Item pack(Long boxId, String rawCode, Long adminId) {
        requireAdmin(adminId);
        String code = requireItemCode(rawCode);
        DonateBox box = lockPackingBox(boxId, "这只箱子已送达，不能再装东西");
        int rows = itemMapper.packIntoBox(boxId, code, LocalDateTime.now(), box.getBizType(), box.getBizId(),
                DonateFlow.ITEM_QUALIFIED, DonateFlow.ITEM_PACKED);
        // 这里的普通读建立在上面两条锁定语句之后，读视图晚于它们，看得见刚刚的结果
        DonateItem item = itemMapper.selectOne(Wrappers.<DonateItem>lambdaQuery().eq(DonateItem::getExclusiveCode, code));
        if (rows != 1) {
            throw new BusinessException(explainPackFailure(box, item));
        }
        traceService.record(item.getShipmentId(), item.getId(), boxId, DonateFlow.ACT_PACK,
                "装入箱子 " + box.getBoxCode(), DonateFlow.OP_ADMIN, adminId);
        return DonateItemService.toItemVO(item, null, null, box.getBoxCode());
    }

    /** 扫物品专属码出箱（装错了）。同样先箱后物；只对仍在装箱中的箱子有效。 */
    @Transactional(rollbackFor = Exception.class)
    public DonateFlowVOs.Item unpack(Long boxId, String rawCode, Long adminId) {
        requireAdmin(adminId);
        String code = requireItemCode(rawCode);
        DonateBox box = lockPackingBox(boxId, "这只箱子已送达，里面的物资不能再拿出来");
        int rows = itemMapper.unpackFromBox(boxId, code, LocalDateTime.now(),
                DonateFlow.ITEM_QUALIFIED, DonateFlow.ITEM_PACKED);
        if (rows != 1) {
            throw new BusinessException("这件物资不在这只箱子里");
        }
        DonateItem item = itemMapper.selectOne(Wrappers.<DonateItem>lambdaQuery().eq(DonateItem::getExclusiveCode, code));
        traceService.record(item.getShipmentId(), item.getId(), boxId, DonateFlow.ACT_UNPACK,
                "从箱子 " + box.getBoxCode() + " 取出", DonateFlow.OP_ADMIN, adminId);
        return DonateItemService.toItemVO(item, null, null, null);
    }

    /**
     * 送达受赠单位（Row 17 第 10 步）。
     *
     * <p><b>三件事的顺序是承重的</b>：</p>
     * <ol>
     *   <li>受赠单位在<b>事务之外</b>先查好——事务里的第一条普通读会定死读视图，查单位若排在最前，
     *       之后读「箱里有哪些物资」就可能看不见在它与箱子 CAS 之间提交的那几次装箱；</li>
     *   <li>事务里<b>第一条语句就是箱子 CAS</b>（取 X）：此后任何装箱都在箱子上排队，箱内物资集合被钉住，
     *       随后建立的读视图正好看到最终集合；</li>
     *   <li><b>按主键逐件改箱内物资</b>，不写 {@code WHERE box_id = ?}：那是范围扫描，RR 下会给扫到的每一行
     *       加 next-key 锁，小表上优化器还可能直接全表扫——撞上一个正拿着自家物资排他锁、在等箱子的装箱事务，就是死锁。</li>
     * </ol>
     *
     * <p><b>空箱不能送达</b>：送达是在给捐赠人写「你的书到了哪所学校」，空箱送达什么都没说明，却会让人以为漏扫了。</p>
     */
    public DonateFlowVOs.Box deliver(Long boxId, Long recipientOrgId, Long adminId) {
        requireAdmin(adminId);
        DonateRecipientOrg org = masterDataService.requireEnabledOrg(recipientOrgId);
        transactionTemplate.execute(s -> {
            doDeliver(boxId, org, adminId);
            return null;
        });
        return detail(boxId);
    }

    private void doDeliver(Long boxId, DonateRecipientOrg org, Long adminId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = boxMapper.update(null, Wrappers.<DonateBox>lambdaUpdate()
                .eq(DonateBox::getId, boxId)
                .eq(DonateBox::getStatus, DonateFlow.BOX_PACKING)
                .set(DonateBox::getStatus, DonateFlow.BOX_DELIVERED)
                .set(DonateBox::getRecipientOrgId, org.getId())
                .set(DonateBox::getRecipientOrgName, org.getName())
                .set(DonateBox::getDeliverTime, now)
                .set(DonateBox::getDeliverBy, adminId)
                .set(DonateBox::getUpdateTime, now));
        if (rows != 1) {
            DonateBox b = boxId == null ? null : boxMapper.selectById(boxId);
            if (b == null) {
                throw new BusinessException("箱子不存在");
            }
            throw new BusinessException("这只箱子已于 " + b.getDeliverTime() + " 送达 " + b.getRecipientOrgName());
        }
        List<DonateItem> items = itemMapper.selectList(Wrappers.<DonateItem>lambdaQuery()
                .eq(DonateItem::getBoxId, boxId)
                .eq(DonateItem::getStatus, DonateFlow.ITEM_PACKED));
        if (items.isEmpty()) {
            throw new BusinessException("空箱不能送达");
        }
        int moved = itemMapper.update(null, Wrappers.<DonateItem>lambdaUpdate()
                .in(DonateItem::getId, items.stream().map(DonateItem::getId).toList())
                .eq(DonateItem::getBoxId, boxId)
                .eq(DonateItem::getStatus, DonateFlow.ITEM_PACKED)
                .set(DonateItem::getStatus, DonateFlow.ITEM_DELIVERED)
                .set(DonateItem::getRecipientOrgId, org.getId())
                .set(DonateItem::getRecipientOrgName, org.getName())
                .set(DonateItem::getDeliverTime, now)
                .set(DonateItem::getUpdateTime, now));
        if (moved != items.size()) {
            throw new BusinessException("箱内物资在送达时发生了变化，请刷新后重试");
        }
        traceService.recordEach(items, boxId, DonateFlow.ACT_DELIVER, i -> "已送达受赠单位：" + org.getName(),
                DonateFlow.OP_ADMIN, adminId);
    }

    /** 给扫码识别用：按箱码找箱子。 */
    public DonateFlowVOs.Box findByCode(String code) {
        DonateBox b = boxMapper.selectOne(Wrappers.<DonateBox>lambdaQuery().eq(DonateBox::getBoxCode, code));
        if (b == null) {
            return null;
        }
        DonateFlowVOs.Box vo = toVO(b, campaignService.titlesOf(List.of(b.getBizId())).get(b.getBizId()));
        vo.setItemCount(countItems(List.of(b.getId())).getOrDefault(b.getId(), 0L).intValue());
        return vo;
    }

    // ================= 内部 =================

    /** 对箱子取 S（当前读）并确认仍在装箱中。送达取的是 X，所以这把锁在，箱子就不会在本事务里被送走。 */
    private DonateBox lockPackingBox(Long boxId, String deliveredMessage) {
        DonateBox box = boxId == null ? null : boxMapper.selectByIdForShare(boxId);
        if (box == null) {
            throw new BusinessException("箱子不存在");
        }
        if (box.getStatus() == null || box.getStatus() != DonateFlow.BOX_PACKING) {
            throw new BusinessException(deliveredMessage);
        }
        return box;
    }

    private static void requireAdmin(Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
    }

    private static String requireItemCode(String raw) {
        String code = DonateCodes.normalize(raw);
        if (DonateCodes.kindOf(code) != DonateCodes.Kind.ITEM) {
            throw new BusinessException("请扫物品专属码（" + DonateCodes.ITEM_PREFIX + " 开头）");
        }
        return code;
    }

    private static String explainPackFailure(DonateBox box, DonateItem item) {
        if (item == null) {
            return "查无此物品码";
        }
        if (!item.getBizType().equals(box.getBizType()) || !item.getBizId().equals(box.getBizId())) {
            return "这件物资属于另一个活动，不能装进这只箱子";
        }
        if (item.getStatus() != null && item.getStatus() == DonateFlow.ITEM_PACKED) {
            return item.getBoxId() != null && item.getBoxId().equals(box.getId())
                    ? "这件物资已经在这只箱子里了" : "这件物资已经装在另一只箱子里，请先出箱";
        }
        return "只有核对合格的物资才能装箱（当前：" + DonateFlow.itemLabel(item.getStatus()) + "）";
    }

    private Map<Long, Long> countItems(List<Long> boxIds) {
        if (boxIds.isEmpty()) {
            return Map.of();
        }
        return itemMapper.selectList(Wrappers.<DonateItem>lambdaQuery()
                        .select(DonateItem::getBoxId)
                        .in(DonateItem::getBoxId, boxIds))
                .stream().collect(Collectors.groupingBy(DonateItem::getBoxId, Collectors.counting()));
    }

    private static DonateFlowVOs.Box toVO(DonateBox b, String campaignTitle) {
        DonateFlowVOs.Box vo = new DonateFlowVOs.Box();
        vo.setId(b.getId());
        vo.setBoxCode(b.getBoxCode());
        vo.setBizType(b.getBizType());
        vo.setBizId(b.getBizId());
        vo.setCampaignTitle(campaignTitle);
        vo.setStatus(b.getStatus());
        vo.setStatusLabel(b.getStatus() != null && b.getStatus() == DonateFlow.BOX_DELIVERED ? "已送达" : "装箱中");
        vo.setRecipientOrgName(b.getRecipientOrgName());
        vo.setDeliverTime(b.getDeliverTime());
        vo.setCreateTime(b.getCreateTime());
        return vo;
    }
}
