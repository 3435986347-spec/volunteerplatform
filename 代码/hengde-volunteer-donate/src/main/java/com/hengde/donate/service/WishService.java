package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.entity.VolunteerNotification;
import com.hengde.auth.service.NotificationService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerContactView;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.config.DonateWishProperties;
import com.hengde.donate.constant.DonateCodes;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.constant.WishFlow;
import com.hengde.donate.dao.DonateItemMapper;
import com.hengde.donate.dao.DonateWishClaimMapper;
import com.hengde.donate.dao.DonateWishMapper;
import com.hengde.donate.dto.ShipmentRegisterDTO;
import com.hengde.donate.dto.WishDTOs;
import com.hengde.donate.entity.DonateItem;
import com.hengde.donate.entity.DonateRecipientOrg;
import com.hengde.donate.entity.DonateWish;
import com.hengde.donate.entity.DonateWishClaim;
import com.hengde.donate.vo.DonateFlowVOs;
import com.hengde.donate.vo.WishVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 圆梦微心愿（Row 12 / Row 35，V3 微心愿批）。
 *
 * <p><b>三道授权都落在服务端</b>：
 * ① 看心愿要已验手机号（Row 12「查看心愿必须登录系统并验证手机号」）；
 * ② 认领要已实名（Row 12 D「认领微心愿则需必须注册通过志愿者」）；
 * ③ 受助人姓名 / 学校只有<b>认领人</b>看得到全文，其他人一律打 *（Row 12 C）——库里是密文，
 *    不是认领人就不解密下发，前端不显示代替不了服务端不下发。</p>
 *
 * <p><b>同一个心愿上的写动作靠行锁排队</b>：认领是心愿行上的 CAS（待认领 → 已认领）；
 * 之后的登记寄出 / 取消 / 撤销 / 实现都先以当前读锁住那条「活」认领（{@code uk_active_wish} 唯一键，只锁一行），
 * 于是它们一定一个在前一个在后——「刚判完没有物资、对方就登记了一单」这种窗口不存在。
 * 「一个心愿同一时刻至多一条有效认领」由生成列唯一键兜底，不靠先查再插。</p>
 *
 * <p><b>物资流转复用捐书那一套</b>（运单 / 物资 / 轨迹 / 扫码到货 / 核对 / 专属码），
 * 来源是 {@code biz_type = 2, biz_id = 认领 id}：认领会被取消、心愿会被别人重新认领，
 * 挂在认领上才分得清「这一包是谁寄给这个心愿的」。</p>
 *
 * @author hengde
 */
@Service
public class WishService {

    static final int MAX_IMPORT_ROWS = 1000;
    static final int EXPORT_LIMIT = 20_000;
    static final int MAX_IMAGES = 9;
    private static final int NO_RETRY = 5;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 「活」的认领：认领中或已实现——正是生成列唯一键 {@code uk_active_wish} 取值的那两态。 */
    private static final List<Integer> LIVE_CLAIM = List.of(WishFlow.CLAIM_ACTIVE, WishFlow.CLAIM_REALIZED);

    private DonateWishMapper wishMapper;
    private DonateWishClaimMapper claimMapper;
    private DonateItemMapper itemMapper;
    private DonateShipmentService shipmentService;
    private DonateMasterDataService masterDataService;
    private DonateTraceService traceService;
    private VolunteerQueryService volunteerQueryService;
    private NotificationService notificationService;
    private CryptoUtil cryptoUtil;
    private DonateWishProperties properties;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setWishMapper(DonateWishMapper wishMapper) {
        this.wishMapper = wishMapper;
    }

    @Autowired
    public void setClaimMapper(DonateWishClaimMapper claimMapper) {
        this.claimMapper = claimMapper;
    }

    @Autowired
    public void setItemMapper(DonateItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    @Autowired
    public void setShipmentService(DonateShipmentService shipmentService) {
        this.shipmentService = shipmentService;
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
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Autowired
    public void setCryptoUtil(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    @Autowired
    public void setProperties(DonateWishProperties properties) {
        this.properties = properties;
    }

    @Autowired
    public void setTransactionTemplate(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    // ================= 后台：心愿资料 =================

    /** 单独上传一个心愿（Row 12 F「单独上传」）。编号不填由系统生成。 */
    public Long create(WishDTOs.Save dto, Long adminId) {
        requireAdmin(adminId);
        DonateWish w = new DonateWish();
        apply(w, dto, null);
        w.setStatus(WishFlow.WISH_OPEN);
        w.setCreateBy(adminId);
        insertWithNo(w, dto.getWishNo());
        return w.getId();
    }

    /**
     * 修改资料：只有<b>还没人认领</b>（待认领 / 已下架）的心愿能改——认领人是看着这份资料认领的，
     * 认领之后再改等于换了一个心愿给他。条件写在 UPDATE 的 WHERE 里，不靠先查再改。
     * 编号留空表示不改。
     */
    public void update(Long id, WishDTOs.Save dto) {
        DonateWish existing = requireWish(id);
        DonateWish w = new DonateWish();
        apply(w, dto, existing);
        String no = StringUtils.hasText(dto.getWishNo()) ? normalizeNo(dto.getWishNo()) : existing.getWishNo();
        int rows;
        try {
            rows = wishMapper.update(null, Wrappers.<DonateWish>lambdaUpdate()
                    .eq(DonateWish::getId, id)
                    .in(DonateWish::getStatus, WishFlow.WISH_OPEN, WishFlow.WISH_TAKEN_DOWN)
                    .set(DonateWish::getWishNo, no)
                    .set(DonateWish::getTitle, w.getTitle())
                    .set(DonateWish::getContent, w.getContent())
                    .set(DonateWish::getStory, w.getStory())
                    .set(DonateWish::getImageUrl, w.getImageUrl())
                    .set(DonateWish::getChildName, w.getChildName())
                    .set(DonateWish::getChildGender, w.getChildGender())
                    .set(DonateWish::getChildAge, w.getChildAge())
                    .set(DonateWish::getChildSchool, w.getChildSchool())
                    .set(DonateWish::getChildGrade, w.getChildGrade())
                    .set(DonateWish::getReportOrgId, w.getReportOrgId())
                    .set(DonateWish::getReportOrgName, w.getReportOrgName())
                    .set(DonateWish::getRemark, w.getRemark())
                    .set(DonateWish::getUpdateTime, LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            throw new BusinessException("心愿编号 " + no + " 已存在");
        }
        if (rows != 1) {
            throw new BusinessException("心愿已被认领或已实现，不能再修改资料（当前："
                    + WishFlow.wishLabel(requireWish(id).getStatus()) + "）");
        }
    }

    /** 下架：只有待认领的可以下架——已认领的要先撤销认领，否则认领人手里的心愿会凭空消失。 */
    public void takeDown(Long id) {
        transit(id, WishFlow.WISH_OPEN, WishFlow.WISH_TAKEN_DOWN, "只有待认领的心愿可以下架");
    }

    /** 重新上架（回到心愿池）。 */
    public void restore(Long id) {
        transit(id, WishFlow.WISH_TAKEN_DOWN, WishFlow.WISH_OPEN, "只有已下架的心愿可以重新上架");
    }

    /**
     * 批量导入（Row 12 G「后台导入微心愿资料」）。<b>全成或全不成</b>：任何一行有问题就一行都不导入，
     * 并逐行列出原因——导入一半最难收拾（哪些进去了、哪些没进去，只能对着表格一条条查）。
     *
     * <p>空行跳过但保留行号：报错里的「第 N 行」对得上 Excel 左边的行号（表头占第 1 行）。
     * 上报单位按<b>名称</b>对上受赠单位主数据（只认启用的），对不上就报错而不是静默置空——
     * 置空之后这个心愿的物资送达时就没有单位可记。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public WishVOs.ImportResult importRows(List<WishDTOs.ImportRow> rows, Long adminId) {
        requireAdmin(adminId);
        List<WishDTOs.ImportRow> all = rows == null ? List.of() : rows;
        WishVOs.ImportResult result = new WishVOs.ImportResult();
        int total = (int) all.stream().filter(r -> !isBlank(r)).count();
        result.setTotal(total);
        if (total == 0) {
            throw new BusinessException("文件里没有心愿数据（第 1 行应为表头，从第 2 行起每行一个心愿）");
        }
        if (total > MAX_IMPORT_ROWS) {
            throw new BusinessException("一次最多导入 " + MAX_IMPORT_ROWS + " 条，请分批导入");
        }
        Set<String> orgNames = all.stream().filter(r -> !isBlank(r) && StringUtils.hasText(r.getReportOrgName()))
                .map(r -> r.getReportOrgName().trim()).collect(Collectors.toSet());
        Map<String, DonateRecipientOrg> orgs = masterDataService.findEnabledOrgsByNames(orgNames);
        Set<String> givenNos = all.stream().filter(r -> !isBlank(r) && StringUtils.hasText(r.getWishNo()))
                .map(r -> r.getWishNo().trim()).collect(Collectors.toSet());
        Set<String> taken = givenNos.isEmpty() ? Set.of()
                : wishMapper.selectList(Wrappers.<DonateWish>lambdaQuery()
                        .select(DonateWish::getWishNo).in(DonateWish::getWishNo, givenNos))
                .stream().map(DonateWish::getWishNo).collect(Collectors.toSet());

        List<DonateWish> toInsert = new ArrayList<>();
        List<String> nos = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < all.size(); i++) {
            WishDTOs.ImportRow r = all.get(i);
            if (isBlank(r)) {
                continue;
            }
            List<String> errs = new ArrayList<>();
            DonateWish w = fromImportRow(r, orgs, errs);
            String no = StringUtils.hasText(r.getWishNo()) ? r.getWishNo().trim() : null;
            if (no != null) {
                if (no.length() > 32 || no.chars().anyMatch(Character::isWhitespace)) {
                    errs.add("心愿编号不超过 32 位且不能含空格");
                } else if (!seen.add(no)) {
                    errs.add("心愿编号 " + no + " 在文件里重复");
                } else if (taken.contains(no)) {
                    errs.add("心愿编号 " + no + " 已存在");
                }
            }
            if (!errs.isEmpty()) {
                result.getErrors().add("第 " + (i + 2) + " 行：" + String.join("；", errs));
                continue;
            }
            w.setStatus(WishFlow.WISH_OPEN);
            w.setCreateBy(adminId);
            toInsert.add(w);
            nos.add(no);
        }
        if (!result.getErrors().isEmpty()) {
            return result;
        }
        for (int i = 0; i < toInsert.size(); i++) {
            insertWithNo(toInsert.get(i), nos.get(i));
        }
        result.setImported(toInsert.size());
        return result;
    }

    /** 后台列表（搜索框：标题 / 编号 / 上报单位；下拉框：状态）。资料明文，带当前认领人。 */
    public PageResult<WishVOs.Wish> listForAdmin(PageQuery query, Integer status, String keyword) {
        IPage<DonateWish> page = wishMapper.selectPage(query.toPage(), adminQuery(status, keyword)
                .orderByDesc(DonateWish::getId));
        Map<Long, DonateWishClaim> live = liveClaimsOf(page.getRecords().stream().map(DonateWish::getId).toList());
        Map<Long, String> names = volunteerQueryService.listNamesByIds(
                live.values().stream().map(DonateWishClaim::getVolunteerId).distinct().toList());
        return PageResult.of(page.convert(w -> {
            WishVOs.Wish vo = toVO(w, true);
            vo.setRemark(w.getRemark());
            vo.setReportOrgId(w.getReportOrgId());
            DonateWishClaim c = live.get(w.getId());
            if (c != null) {
                vo.setClaimId(c.getId());
                vo.setClaimTime(c.getClaimTime());
                vo.setClaimerName(names.get(c.getVolunteerId()));
            }
            return vo;
        }));
    }

    /** 后台详情：资料全文 + 历次认领（认领人姓名电话、寄来的包裹、物资与轨迹）。 */
    public WishVOs.AdminDetail detailForAdmin(Long id) {
        DonateWish w = requireWish(id);
        WishVOs.Wish vo = toVO(w, true);
        vo.setRemark(w.getRemark());
        vo.setReportOrgId(w.getReportOrgId());
        List<DonateWishClaim> claims = claimMapper.selectList(Wrappers.<DonateWishClaim>lambdaQuery()
                .eq(DonateWishClaim::getWishId, id)
                .orderByDesc(DonateWishClaim::getId));
        Map<Long, VolunteerContactView> contacts = claims.isEmpty() ? Map.of()
                : volunteerQueryService.listContactsByIds(
                        claims.stream().map(DonateWishClaim::getVolunteerId).collect(Collectors.toSet()));
        Map<Long, List<DonateFlowVOs.Shipment>> shipments = shipmentService.listByBiz(DonateFlow.BIZ_WISH,
                claims.stream().map(DonateWishClaim::getId).toList());
        WishVOs.AdminDetail d = new WishVOs.AdminDetail();
        d.setWish(vo);
        for (DonateWishClaim c : claims) {
            WishVOs.Claim cv = toClaimVO(c);
            VolunteerContactView contact = contacts.get(c.getVolunteerId());
            cv.setClaimerName(contact == null ? null : contact.realName());
            cv.setClaimerPhone(contact == null ? null : contact.phone());
            cv.setShipments(new ArrayList<>(shipments.getOrDefault(c.getId(), List.of())));
            if (LIVE_CLAIM.contains(c.getStatus())) {
                vo.setClaimId(c.getId());
                vo.setClaimTime(c.getClaimTime());
                vo.setClaimerName(cv.getClaimerName());
            }
            d.getClaims().add(cv);
        }
        return d;
    }

    /**
     * 后台撤销认领（Row 12 F「取消认领」）。心愿回到心愿池，认领人收到一条站内提示（带原因）。
     *
     * <p><b>认领人寄来的东西还「活着」就不能撤</b>（在途 / 待核对 / 合格——见 {@code countLiveGoods}）：
     * 撤了之后那些物资挂在一条已撤销的认领下，送不出去也退不回去。在途的包裹可由认领人自己取消；
     * 全被判不合格的包裹不挡（它们走退回流程，与认领无关）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void revokeClaim(Long wishId, String reason, Long adminId) {
        requireAdmin(adminId);
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("请填写撤销原因——认领人会看到");
        }
        if (reason.trim().length() > 512) {
            throw new BusinessException("撤销原因不超过 512 字");
        }
        DonateWishClaim claim = claimMapper.selectLiveByWishForUpdate(wishId);
        if (claim == null) {
            throw new BusinessException("这个心愿当前没有进行中的认领");
        }
        if (claim.getStatus() != WishFlow.CLAIM_ACTIVE) {
            throw new BusinessException("心愿已经实现，不能撤销认领");
        }
        if (shipmentService.countLiveGoods(DonateFlow.BIZ_WISH, claim.getId()) > 0) {
            throw new BusinessException("认领人寄来的物资还在流转中或已核对合格，不能撤销认领；"
                    + "还没到货的包裹可由认领人自行取消");
        }
        LocalDateTime now = LocalDateTime.now();
        endClaim(claim.getId(), WishFlow.CLAIM_REVOKED, now, adminId, reason.trim());
        reopenWish(wishId, now);
        DonateWish w = wishMapper.selectById(wishId);
        notificationService.notify(claim.getVolunteerId(), VolunteerNotification.TYPE_WISH_CLAIM_REVOKED,
                "微心愿认领已被撤销",
                "你认领的心愿「" + w.getTitle() + "」已被协会撤销认领，心愿回到了心愿池。原因：" + reason.trim(),
                VolunteerNotification.BIZ_WISH_CLAIM, claim.getId());
    }

    /**
     * 心愿实现（Row 12 G「给捐赠人反馈物资发放图片」）：物资发放出去、上传发放图片，认领与心愿同时置「已实现」。
     *
     * <p>前置条件（每条都是「否则就会有东西卡住」）：
     * ① 没有未到货 / 未核对的包裹——否则那一包到了之后没有下一步（当前读锁住这些运单，与扫码到货 / 核对串行化）；
     * ② 至少一件核对合格的物资；③ 合格物资都已生成专属码——Row 12 G 的顺序是「专属条码 → 反馈发放」，
     * 没有码的东西发出去就追溯不到。合格物资 → 已送达，受捐单位记心愿的上报单位（快照）。</p>
     *
     * <p>排行榜按认领上的 {@code realize_time} 切周期（{@code DonateWishClaimMapper.topRealized}）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void realize(Long wishId, List<String> images, Long adminId) {
        requireAdmin(adminId);
        List<String> imgs = normalizeImages(images);
        DonateWishClaim claim = claimMapper.selectLiveByWishForUpdate(wishId);
        if (claim == null) {
            throw new BusinessException("这个心愿还没有人认领，不能标记为已实现");
        }
        if (claim.getStatus() == WishFlow.CLAIM_REALIZED) {
            throw new BusinessException("这个心愿已于 " + claim.getRealizeTime().format(TIME) + " 实现");
        }
        if (shipmentService.countUnsettledShipments(DonateFlow.BIZ_WISH, claim.getId()) > 0) {
            throw new BusinessException("还有包裹未到货或未核对，请处理完再标记实现（在途的包裹也可由认领人取消）");
        }
        List<DonateItem> items = itemMapper.selectList(Wrappers.<DonateItem>lambdaQuery()
                .eq(DonateItem::getBizType, DonateFlow.BIZ_WISH)
                .eq(DonateItem::getBizId, claim.getId())
                .eq(DonateItem::getStatus, DonateFlow.ITEM_QUALIFIED));
        if (items.isEmpty()) {
            throw new BusinessException("还没有核对合格的物资，不能标记为已实现");
        }
        long noCode = items.stream().filter(i -> i.getExclusiveCode() == null).count();
        if (noCode > 0) {
            throw new BusinessException("还有 " + noCode + " 件合格物资没有生成专属条码，请先生成并贴好标签再发放");
        }
        DonateWish wish = wishMapper.selectById(wishId);
        LocalDateTime now = LocalDateTime.now();
        int moved = itemMapper.update(null, Wrappers.<DonateItem>lambdaUpdate()
                .in(DonateItem::getId, items.stream().map(DonateItem::getId).toList())
                .eq(DonateItem::getStatus, DonateFlow.ITEM_QUALIFIED)
                .set(DonateItem::getStatus, DonateFlow.ITEM_DELIVERED)
                .set(DonateItem::getRecipientOrgId, wish.getReportOrgId())
                .set(DonateItem::getRecipientOrgName, wish.getReportOrgName())
                .set(DonateItem::getDeliverTime, now)
                .set(DonateItem::getUpdateTime, now));
        if (moved != items.size()) {
            throw new BusinessException("物资状态在发放时发生了变化，请刷新后重试");
        }
        String where = StringUtils.hasText(wish.getReportOrgName()) ? "（" + wish.getReportOrgName() + "）" : "";
        traceService.recordEach(items, null, DonateFlow.ACT_DELIVER, i -> "心愿已实现，物资已发放" + where,
                DonateFlow.OP_ADMIN, adminId);
        int claimRows = claimMapper.update(null, Wrappers.<DonateWishClaim>lambdaUpdate()
                .eq(DonateWishClaim::getId, claim.getId())
                .eq(DonateWishClaim::getStatus, WishFlow.CLAIM_ACTIVE)
                .set(DonateWishClaim::getStatus, WishFlow.CLAIM_REALIZED)
                .set(DonateWishClaim::getRealizeTime, now)
                .set(DonateWishClaim::getUpdateTime, now));
        int wishRows = wishMapper.update(null, Wrappers.<DonateWish>lambdaUpdate()
                .eq(DonateWish::getId, wishId)
                .eq(DonateWish::getStatus, WishFlow.WISH_CLAIMED)
                .set(DonateWish::getStatus, WishFlow.WISH_REALIZED)
                .set(DonateWish::getRealizeTime, now)
                .set(DonateWish::getFeedbackImages, String.join("\n", imgs))
                .set(DonateWish::getUpdateTime, now));
        if (claimRows != 1 || wishRows != 1) {
            throw new BusinessException("心愿状态已变化，请刷新后重试");
        }
        notificationService.notify(claim.getVolunteerId(), VolunteerNotification.TYPE_WISH_REALIZED,
                "你认领的微心愿已实现",
                "心愿「" + wish.getTitle() + "」的物资已发放到孩子手中，发放照片可在微心愿中心查看。谢谢你！",
                VolunteerNotification.BIZ_WISH_CLAIM, claim.getId());
    }

    /** 批量下载（Row 12 F）。含未成年人资料，受 {@code donate:wish} 保护；超过上限报错，<b>不静默截断</b>。 */
    public List<WishVOs.ExportRow> exportRows(Integer status, String keyword) {
        List<DonateWish> wishes = wishMapper.selectList(adminQuery(status, keyword)
                .orderByDesc(DonateWish::getId)
                .last("LIMIT " + (EXPORT_LIMIT + 1)));
        if (wishes.size() > EXPORT_LIMIT) {
            throw new BusinessException("结果超过 " + EXPORT_LIMIT + " 行，请按状态缩小范围后分批导出");
        }
        Map<Long, DonateWishClaim> live = liveClaimsOf(wishes.stream().map(DonateWish::getId).toList());
        Map<Long, String> names = volunteerQueryService.listNamesByIds(
                live.values().stream().map(DonateWishClaim::getVolunteerId).distinct().toList());
        return wishes.stream().map(w -> {
            WishVOs.ExportRow e = new WishVOs.ExportRow();
            e.setWishNo(w.getWishNo());
            e.setTitle(w.getTitle());
            e.setChildName(decrypt(w.getChildName()));
            e.setChildGender(genderLabel(w.getChildGender()));
            e.setChildAge(w.getChildAge());
            e.setChildSchool(decrypt(w.getChildSchool()));
            e.setChildGrade(w.getChildGrade());
            e.setReportOrgName(w.getReportOrgName());
            e.setStatus(WishFlow.wishLabel(w.getStatus()));
            DonateWishClaim c = live.get(w.getId());
            if (c != null) {
                e.setClaimerName(names.get(c.getVolunteerId()));
                e.setClaimTime(c.getClaimTime() == null ? null : c.getClaimTime().format(TIME));
            }
            e.setRealizeTime(w.getRealizeTime() == null ? null : w.getRealizeTime().format(TIME));
            return e;
        }).toList();
    }

    // ================= 志愿者端 =================

    /**
     * 心愿列表：tab 0 心愿池 / 1 已认领 / 2 已实现（Row 12 前三个页签；第四个「榜单」是 honor 的排行榜）。
     * 姓名、学校打 *，是我认领的除外。
     */
    public PageResult<WishVOs.Wish> listForVolunteer(Long volunteerId, Integer tab, String keyword, PageQuery query) {
        requireViewer(volunteerId);
        int status = statusOfTab(tab);
        LambdaQueryWrapper<DonateWish> q = Wrappers.<DonateWish>lambdaQuery()
                .eq(DonateWish::getStatus, status)
                .and(StringUtils.hasText(keyword), k -> k.like(DonateWish::getTitle, keyword.trim())
                        .or().like(DonateWish::getContent, keyword.trim())
                        .or().like(DonateWish::getWishNo, keyword.trim()));
        if (status == WishFlow.WISH_REALIZED) {
            q.orderByDesc(DonateWish::getRealizeTime);
        }
        q.orderByDesc(DonateWish::getId);
        IPage<DonateWish> page = wishMapper.selectPage(query.toPage(), q);
        List<Long> ids = page.getRecords().stream().map(DonateWish::getId).toList();
        Set<Long> mine = ids.isEmpty() ? Set.of()
                : claimMapper.selectList(Wrappers.<DonateWishClaim>lambdaQuery()
                        .select(DonateWishClaim::getWishId)
                        .eq(DonateWishClaim::getVolunteerId, volunteerId)
                        .in(DonateWishClaim::getWishId, ids)
                        .in(DonateWishClaim::getStatus, LIVE_CLAIM))
                .stream().map(DonateWishClaim::getWishId).collect(Collectors.toSet());
        return PageResult.of(page.convert(w -> toVolunteerVO(w, mine.contains(w.getId()))));
    }

    /** 心愿详情：认领人给全部资料 + 物资接收地址（Row 12 C），其他人打 *。已下架的与不存在同一句话。 */
    public WishVOs.Wish detailForVolunteer(Long wishId, Long volunteerId) {
        requireViewer(volunteerId);
        DonateWish w = wishId == null ? null : wishMapper.selectById(wishId);
        if (w == null || w.getStatus() == WishFlow.WISH_TAKEN_DOWN) {
            throw new BusinessException("心愿不存在");
        }
        return toVolunteerVO(w, isMine(wishId, volunteerId));
    }

    /**
     * 认领（Row 12 D）：须已实名、已验手机号。心愿行 CAS 待认领 → 已认领，再插一条「认领中」。
     * 两个人同时点，CAS 只让一个过；另一个拿到「刚被别人认领」。
     */
    public WishVOs.Wish claim(Long wishId, Long volunteerId) {
        if (volunteerId == null) {
            throw new BusinessException("志愿者不能为空");
        }
        // 资格校验刻意放在事务外，见 doClaim 的注释：事务里的第一条语句必须是那条 CAS
        if (!volunteerQueryService.filterActiveRegistered(List.of(volunteerId)).contains(volunteerId)) {
            throw new BusinessException("认领微心愿需先完成志愿者实名注册");
        }
        requireViewer(volunteerId);
        return transactionTemplate.execute(s -> doClaim(wishId, volunteerId));
    }

    /**
     * 认领的事务体。<b>事务里的第一条语句必须是那条 CAS</b>（它是当前读）——
     * 资格校验那两次普通读若留在事务内，RR 的读视图就定死在别人提交之前，
     * CAS 失败后的复核会读到「还没人认领」，于是把「刚被别人认领」报成「这个心愿已经实现了」。
     * 8 个人同时抢认领的并发用例当场撞出来的，与捐书批「生成专属码」是同一个形状。
     */
    private WishVOs.Wish doClaim(Long wishId, Long volunteerId) {
        LocalDateTime now = LocalDateTime.now();
        int rows = wishMapper.update(null, Wrappers.<DonateWish>lambdaUpdate()
                .eq(DonateWish::getId, wishId)
                .eq(DonateWish::getStatus, WishFlow.WISH_OPEN)
                .set(DonateWish::getStatus, WishFlow.WISH_CLAIMED)
                .set(DonateWish::getUpdateTime, now));
        if (rows != 1) {
            DonateWish w = wishId == null ? null : wishMapper.selectById(wishId);
            if (w == null || w.getStatus() == WishFlow.WISH_TAKEN_DOWN) {
                throw new BusinessException("心愿不存在");
            }
            if (w.getStatus() == WishFlow.WISH_CLAIMED) {
                throw new BusinessException(isMine(wishId, volunteerId) ? "你已经认领了这个心愿"
                        : "这个心愿刚刚被别人认领了，看看别的心愿吧");
            }
            throw new BusinessException("这个心愿已经实现了");
        }
        DonateWishClaim c = new DonateWishClaim();
        c.setWishId(wishId);
        c.setVolunteerId(volunteerId);
        c.setStatus(WishFlow.CLAIM_ACTIVE);
        c.setClaimTime(now);
        try {
            claimMapper.insert(c);
        } catch (DuplicateKeyException e) {
            // 心愿行的 CAS 已经保证了独占，走到这里说明两张表的状态对不上——宁可报错回滚，也不让一个心愿挂两条有效认领
            throw new BusinessException("这个心愿刚刚被别人认领了，看看别的心愿吧");
        }
        return toVolunteerVO(wishMapper.selectById(wishId), true);
    }

    /**
     * 取消认领（释放占位，心愿回到心愿池）。寄出的物资还「活着」（在途 / 已被签收）就不能取消——
     * 在途的包裹先在「我的运单」里取消即可。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelClaim(Long wishId, Long volunteerId) {
        DonateWishClaim claim = claimMapper.selectLiveByWishForUpdate(wishId);
        if (claim == null || !claim.getVolunteerId().equals(volunteerId)) {
            throw new BusinessException("你没有认领这个心愿");
        }
        if (claim.getStatus() != WishFlow.CLAIM_ACTIVE) {
            throw new BusinessException("心愿已经实现，不能取消认领");
        }
        if (shipmentService.countLiveGoods(DonateFlow.BIZ_WISH, claim.getId()) > 0) {
            throw new BusinessException("你寄出的物资还在路上或已被协会签收，不能取消认领；"
                    + "还没到货的包裹可以先在「我的运单」里取消");
        }
        LocalDateTime now = LocalDateTime.now();
        endClaim(claim.getId(), WishFlow.CLAIM_CANCELLED, now, null, null);
        reopenWish(wishId, now);
    }

    /**
     * 为认领的心愿登记寄出物资（Row 12 G「认领 → 录入物资 → 包裹」）。先锁住我的有效认领，再建运单——
     * 与取消 / 撤销 / 实现串行化。运单、物资、轨迹与捐书同一套，来源记为 {@code (2, 认领 id)}。
     */
    @Transactional(rollbackFor = Exception.class)
    public DonateFlowVOs.Shipment registerShipment(Long wishId, Long volunteerId, ShipmentRegisterDTO dto) {
        DonateWishClaim claim = claimMapper.selectLiveByWishForUpdate(wishId);
        if (claim == null || !claim.getVolunteerId().equals(volunteerId)) {
            throw new BusinessException("你没有认领这个心愿，不能为它寄送物资");
        }
        if (claim.getStatus() != WishFlow.CLAIM_ACTIVE) {
            throw new BusinessException("心愿已经实现，不需要再寄送物资了");
        }
        return shipmentService.createShipment(DonateFlow.BIZ_WISH, claim.getId(), volunteerId, dto);
    }

    /**
     * 微心愿中心（Row 35）：我的每一次认领 + 寄出的包裹、物资明细与流转轨迹。
     * 已取消 / 已撤销的那几条，心愿资料重新打 *——不再是认领人，就不再有看全文的理由。
     */
    public PageResult<WishVOs.Claim> myClaims(Long volunteerId, PageQuery query) {
        IPage<DonateWishClaim> page = claimMapper.selectPage(query.toPage(), Wrappers.<DonateWishClaim>lambdaQuery()
                .eq(DonateWishClaim::getVolunteerId, volunteerId)
                .orderByDesc(DonateWishClaim::getId));
        List<DonateWishClaim> claims = page.getRecords();
        Set<Long> wishIds = claims.stream().map(DonateWishClaim::getWishId).collect(Collectors.toSet());
        Map<Long, DonateWish> wishes = wishIds.isEmpty() ? Map.of()
                : wishMapper.selectList(Wrappers.<DonateWish>lambdaQuery().in(DonateWish::getId, wishIds))
                .stream().collect(Collectors.toMap(DonateWish::getId, Function.identity()));
        Map<Long, List<DonateFlowVOs.Shipment>> shipments = shipmentService.listByBiz(DonateFlow.BIZ_WISH,
                claims.stream().map(DonateWishClaim::getId).toList());
        return PageResult.of(page.convert(c -> {
            WishVOs.Claim cv = toClaimVO(c);
            DonateWish w = wishes.get(c.getWishId());
            if (w != null) {
                cv.setWish(toVolunteerVO(w, LIVE_CLAIM.contains(c.getStatus())));
            }
            cv.setShipments(new ArrayList<>(shipments.getOrDefault(c.getId(), List.of())));
            return cv;
        }));
    }

    // ================= 内部 =================

    private void transit(Long id, int from, int to, String rejectMessage) {
        int rows = wishMapper.update(null, Wrappers.<DonateWish>lambdaUpdate()
                .eq(DonateWish::getId, id)
                .eq(DonateWish::getStatus, from)
                .set(DonateWish::getStatus, to)
                .set(DonateWish::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException(rejectMessage + "（当前：" + WishFlow.wishLabel(requireWish(id).getStatus()) + "）");
        }
    }

    /** 认领「认领中 → 已取消 / 已撤销」。调用方已持有这条认领的行锁，失败只可能是数据被手工改过。 */
    private void endClaim(Long claimId, int to, LocalDateTime now, Long by, String reason) {
        int rows = claimMapper.update(null, Wrappers.<DonateWishClaim>lambdaUpdate()
                .eq(DonateWishClaim::getId, claimId)
                .eq(DonateWishClaim::getStatus, WishFlow.CLAIM_ACTIVE)
                .set(DonateWishClaim::getStatus, to)
                .set(DonateWishClaim::getCancelTime, now)
                .set(DonateWishClaim::getCancelBy, by)
                .set(DonateWishClaim::getCancelReason, reason)
                .set(DonateWishClaim::getUpdateTime, now));
        if (rows != 1) {
            throw new BusinessException("认领状态已变化，请刷新后重试");
        }
    }

    /** 心愿「已认领 → 待认领」（回到心愿池）。 */
    private void reopenWish(Long wishId, LocalDateTime now) {
        int rows = wishMapper.update(null, Wrappers.<DonateWish>lambdaUpdate()
                .eq(DonateWish::getId, wishId)
                .eq(DonateWish::getStatus, WishFlow.WISH_CLAIMED)
                .set(DonateWish::getStatus, WishFlow.WISH_OPEN)
                .set(DonateWish::getUpdateTime, now));
        if (rows != 1) {
            throw new BusinessException("心愿状态与认领对不上（不在「已认领」），请联系管理员核查");
        }
    }

    private void insertWithNo(DonateWish w, String given) {
        if (StringUtils.hasText(given)) {
            w.setWishNo(normalizeNo(given));
            try {
                wishMapper.insert(w);
            } catch (DuplicateKeyException e) {
                throw new BusinessException("心愿编号 " + w.getWishNo() + " 已存在");
            }
            return;
        }
        for (int attempt = 0; attempt < NO_RETRY; attempt++) {
            w.setWishNo(DonateCodes.newWishNo());
            try {
                wishMapper.insert(w);
                return;
            } catch (DuplicateKeyException e) {
                w.setId(null);
            }
        }
        throw new BusinessException("心愿编号生成失败，请重试");
    }

    /** 表单与导入共用的字段落位（姓名、学校加密）。{@code existing} 非空表示修改。 */
    private void apply(DonateWish w, WishDTOs.Save dto, DonateWish existing) {
        if (dto == null) {
            throw new BusinessException("心愿资料不能为空");
        }
        List<String> errs = new ArrayList<>();
        String title = trimToNull(dto.getTitle());
        String name = trimToNull(dto.getChildName());
        if (title == null) {
            errs.add("请填写心愿标题");
        } else if (title.length() > 128) {
            errs.add("标题不超过 128 字");
        }
        if (name == null) {
            errs.add("请填写受助人姓名");
        } else if (name.length() > 32) {
            errs.add("姓名过长");
        }
        if (dto.getChildGender() != null && dto.getChildGender() != 1 && dto.getChildGender() != 2) {
            errs.add("性别只能是 1 男 / 2 女");
        }
        checkAge(dto.getChildAge(), errs);
        checkLengths(dto.getContent(), dto.getImageUrl(), dto.getChildSchool(), dto.getChildGrade(), dto.getRemark(), errs);
        if (!errs.isEmpty()) {
            throw new BusinessException(String.join("；", errs));
        }
        fill(w, title, name, dto.getChildGender(), dto.getChildAge(), dto.getContent(), dto.getStory(),
                dto.getImageUrl(), dto.getChildSchool(), dto.getChildGrade(), dto.getRemark());
        // 上报单位：沿用原单位（哪怕它后来停用了）不算「选了一个停用的单位」——改个错别字不该被迫换单位
        if (dto.getReportOrgId() == null) {
            w.setReportOrgId(null);
            w.setReportOrgName(null);
        } else if (existing != null && dto.getReportOrgId().equals(existing.getReportOrgId())) {
            w.setReportOrgId(existing.getReportOrgId());
            w.setReportOrgName(existing.getReportOrgName());
        } else {
            DonateRecipientOrg org = masterDataService.requireEnabledOrg(dto.getReportOrgId());
            w.setReportOrgId(org.getId());
            w.setReportOrgName(org.getName());
        }
    }

    private DonateWish fromImportRow(WishDTOs.ImportRow r, Map<String, DonateRecipientOrg> orgs, List<String> errs) {
        DonateWish w = new DonateWish();
        String title = trimToNull(r.getTitle());
        String name = trimToNull(r.getChildName());
        if (title == null) {
            errs.add("缺少标题");
        } else if (title.length() > 128) {
            errs.add("标题超过 128 字");
        }
        if (name == null) {
            errs.add("缺少姓名");
        } else if (name.length() > 32) {
            errs.add("姓名过长");
        }
        Integer gender = null;
        String g = trimToNull(r.getChildGender());
        if (g != null) {
            gender = parseGender(g);
            if (gender == null) {
                errs.add("性别只能填「男」或「女」");
            }
        }
        checkAge(r.getChildAge(), errs);
        checkLengths(r.getContent(), r.getImageUrl(), r.getChildSchool(), r.getChildGrade(), r.getRemark(), errs);
        String orgName = trimToNull(r.getReportOrgName());
        DonateRecipientOrg org = orgName == null ? null : orgs.get(orgName);
        if (orgName != null && org == null) {
            errs.add("上报单位「" + orgName + "」不在受赠单位列表里（或已停用），请先在受赠单位里添加");
        }
        if (!errs.isEmpty()) {
            return w;
        }
        fill(w, title, name, gender, r.getChildAge(), r.getContent(), r.getStory(), r.getImageUrl(),
                r.getChildSchool(), r.getChildGrade(), r.getRemark());
        w.setReportOrgId(org == null ? null : org.getId());
        w.setReportOrgName(org == null ? null : org.getName());
        return w;
    }

    private void fill(DonateWish w, String title, String name, Integer gender, Integer age, String content,
                      String story, String imageUrl, String school, String grade, String remark) {
        w.setTitle(title);
        w.setContent(trimToNull(content));
        w.setStory(trimToNull(story));
        w.setImageUrl(trimToNull(imageUrl));
        w.setChildName(cryptoUtil.encrypt(name));
        w.setChildGender(gender);
        w.setChildAge(age);
        String s = trimToNull(school);
        w.setChildSchool(s == null ? null : cryptoUtil.encrypt(s));
        w.setChildGrade(trimToNull(grade));
        w.setRemark(trimToNull(remark));
    }

    private static void checkAge(Integer age, List<String> errs) {
        if (age != null && (age < 1 || age > 30)) {
            errs.add("年龄不正确");
        }
    }

    /**
     * 列宽兜底。学校是<b>密文</b>存进 VARCHAR(512) 的：AES-GCM 加 28 字节再 Base64 放大 4/3，
     * 64 个汉字（192 字节）→ 约 296 字符，留足余量；放到 128 字就会在加密后超列宽、以 500 告终。
     */
    private static void checkLengths(String content, String imageUrl, String school, String grade, String remark,
                                     List<String> errs) {
        checkLen(content, 512, "心愿内容", errs);
        checkLen(imageUrl, 512, "图片地址", errs);
        checkLen(school, 64, "学校", errs);
        checkLen(grade, 32, "年级", errs);
        checkLen(remark, 512, "备注", errs);
    }

    private static void checkLen(String v, int max, String what, List<String> errs) {
        if (v != null && v.trim().length() > max) {
            errs.add(what + "不超过 " + max + " 字");
        }
    }

    private static Integer parseGender(String g) {
        if ("男".equals(g) || "1".equals(g)) {
            return 1;
        }
        if ("女".equals(g) || "2".equals(g)) {
            return 2;
        }
        return null;
    }

    private static String genderLabel(Integer g) {
        if (g == null) {
            return null;
        }
        return g == 1 ? "男" : g == 2 ? "女" : null;
    }

    private static String normalizeNo(String raw) {
        String no = raw.trim();
        if (no.length() > 32 || no.chars().anyMatch(Character::isWhitespace)) {
            throw new BusinessException("心愿编号不超过 32 位且不能含空格");
        }
        return no;
    }

    private static List<String> normalizeImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            throw new BusinessException("请上传 1~" + MAX_IMAGES + " 张物资发放图片");
        }
        if (images.size() > MAX_IMAGES) {
            throw new BusinessException("发放图片最多 " + MAX_IMAGES + " 张");
        }
        List<String> out = new ArrayList<>();
        for (String s : images) {
            String v = trimToNull(s);
            if (v == null) {
                throw new BusinessException("图片地址不能为空");
            }
            // 换行是存储分隔符：地址里混进换行，读出来就会被拆成两张图
            if (v.length() > 512 || v.contains("\n") || v.contains("\r")) {
                throw new BusinessException("图片地址不正确");
            }
            out.add(v);
        }
        return out;
    }

    private static boolean isBlank(WishDTOs.ImportRow r) {
        return r == null || (trimToNull(r.getWishNo()) == null && trimToNull(r.getTitle()) == null
                && trimToNull(r.getChildName()) == null && trimToNull(r.getContent()) == null
                && trimToNull(r.getReportOrgName()) == null && r.getChildAge() == null);
    }

    private LambdaQueryWrapper<DonateWish> adminQuery(Integer status, String keyword) {
        return Wrappers.<DonateWish>lambdaQuery()
                .eq(status != null, DonateWish::getStatus, status)
                .and(StringUtils.hasText(keyword), k -> k.like(DonateWish::getTitle, keyword.trim())
                        .or().like(DonateWish::getWishNo, keyword.trim())
                        .or().like(DonateWish::getReportOrgName, keyword.trim()));
    }

    /** 各心愿当前的「活」认领（认领中或已实现，至多一条——唯一键保证）。 */
    private Map<Long, DonateWishClaim> liveClaimsOf(List<Long> wishIds) {
        if (wishIds.isEmpty()) {
            return Map.of();
        }
        return claimMapper.selectList(Wrappers.<DonateWishClaim>lambdaQuery()
                        .in(DonateWishClaim::getWishId, wishIds)
                        .in(DonateWishClaim::getStatus, LIVE_CLAIM))
                .stream().collect(Collectors.toMap(DonateWishClaim::getWishId, Function.identity(), (a, b) -> a));
    }

    private boolean isMine(Long wishId, Long volunteerId) {
        return claimMapper.selectCount(Wrappers.<DonateWishClaim>lambdaQuery()
                .eq(DonateWishClaim::getWishId, wishId)
                .eq(DonateWishClaim::getVolunteerId, volunteerId)
                .in(DonateWishClaim::getStatus, LIVE_CLAIM)) > 0;
    }

    private void requireViewer(Long volunteerId) {
        if (volunteerId == null || !volunteerQueryService.hasVerifiedPhone(volunteerId)) {
            throw new BusinessException("查看微心愿需要先验证手机号");
        }
    }

    private static int statusOfTab(Integer tab) {
        if (tab == null || tab == 0) {
            return WishFlow.WISH_OPEN;
        }
        if (tab == 1) {
            return WishFlow.WISH_CLAIMED;
        }
        if (tab == 2) {
            return WishFlow.WISH_REALIZED;
        }
        throw new BusinessException("tab 只能是 0 心愿池 / 1 已认领 / 2 已实现");
    }

    private DonateWish requireWish(Long id) {
        DonateWish w = id == null ? null : wishMapper.selectById(id);
        if (w == null) {
            throw new BusinessException("心愿不存在");
        }
        return w;
    }

    private static void requireAdmin(Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
    }

    /**
     * 志愿者看到的心愿：认领人给全文 + 物资接收地址 + 发放照片；其他人姓名 / 学校打 *，
     * 备注（协会内部记录，可能写着家庭情况）与发放照片（孩子本人入镜）一律不给。
     */
    private WishVOs.Wish toVolunteerVO(DonateWish w, boolean mine) {
        WishVOs.Wish vo = toVO(w, mine);
        vo.setClaimedByMe(mine);
        if (mine) {
            vo.setRecvName(properties.getRecvName());
            vo.setRecvPhone(properties.getRecvPhone());
            vo.setRecvAddress(properties.getRecvAddress());
        } else {
            vo.getFeedbackImages().clear();
        }
        return vo;
    }

    private WishVOs.Wish toVO(DonateWish w, boolean full) {
        WishVOs.Wish vo = new WishVOs.Wish();
        vo.setId(w.getId());
        vo.setWishNo(w.getWishNo());
        vo.setTitle(w.getTitle());
        vo.setContent(w.getContent());
        vo.setStory(w.getStory());
        vo.setImageUrl(w.getImageUrl());
        String name = decrypt(w.getChildName());
        String school = decrypt(w.getChildSchool());
        vo.setChildName(full ? name : WishFlow.maskName(name));
        vo.setChildSchool(full ? school : WishFlow.maskSchool(school));
        vo.setMasked(!full);
        vo.setChildGender(w.getChildGender());
        vo.setChildAge(w.getChildAge());
        vo.setChildGrade(w.getChildGrade());
        vo.setReportOrgName(w.getReportOrgName());
        vo.setStatus(w.getStatus());
        vo.setStatusLabel(WishFlow.wishLabel(w.getStatus()));
        vo.setCreateTime(w.getCreateTime());
        vo.setRealizeTime(w.getRealizeTime());
        if (StringUtils.hasText(w.getFeedbackImages())) {
            vo.setFeedbackImages(new ArrayList<>(Arrays.asList(w.getFeedbackImages().split("\n"))));
        }
        return vo;
    }

    private static WishVOs.Claim toClaimVO(DonateWishClaim c) {
        WishVOs.Claim cv = new WishVOs.Claim();
        cv.setId(c.getId());
        cv.setStatus(c.getStatus());
        cv.setStatusLabel(WishFlow.claimLabel(c.getStatus()));
        cv.setClaimTime(c.getClaimTime());
        cv.setRealizeTime(c.getRealizeTime());
        cv.setCancelTime(c.getCancelTime());
        cv.setCancelReason(c.getCancelReason());
        return cv;
    }

    private String decrypt(String cipher) {
        return StringUtils.hasText(cipher) ? cryptoUtil.decrypt(cipher) : null;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
