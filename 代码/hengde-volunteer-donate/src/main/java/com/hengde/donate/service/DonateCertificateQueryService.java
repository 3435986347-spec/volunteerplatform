package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.donate.constant.DonateCertRefs;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.constant.DonationFlow;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.constant.WishFlow;
import com.hengde.donate.dao.DonateCampaignMapper;
import com.hengde.donate.dao.DonateDonationMapper;
import com.hengde.donate.dao.DonateItemMapper;
import com.hengde.donate.dao.DonatePairMappers.DonateCrowdfundMapper;
import com.hengde.donate.dao.DonateShipmentMapper;
import com.hengde.donate.dao.DonateWishClaimMapper;
import com.hengde.donate.dao.DonateWishMapper;
import com.hengde.donate.entity.DonateCampaign;
import com.hengde.donate.entity.DonateCrowdfund;
import com.hengde.donate.entity.DonateDonation;
import com.hengde.donate.entity.DonateItem;
import com.hengde.donate.entity.DonateShipment;
import com.hengde.donate.entity.DonateWish;
import com.hengde.donate.entity.DonateWishClaim;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 捐赠证书的<b>跨模块只读窄接口</b>——给 honor 出证、渲染、列表展示用（协会 2026-10-16 答复五 乙-1 / 乙-2）。
 *
 * <p>「什么算够格出证、证书上那句话怎么说」是捐赠域的语义，收在这里；honor 只发证、套印、存文件——
 * 与 {@link DonatePairQueryService}、activity 的 {@code ActivityCertificateQueryService} 同一形状。</p>
 *
 * <p><b>够格的口径</b>（任何一条不满足都返回 null，由 honor 拒绝出证——宁可不出，不能给没发生的事盖章）：</p>
 * <ul>
 *   <li>结对：登记「已成立」——<b>证书写结对成功，不写金额</b>（乙-1：在结对成功时出、不是付款后）；</li>
 *   <li>微心愿：那条认领「已实现」；</li>
 *   <li>公益捐书 / 众筹捐物：那个包裹已核对、且至少有一件合格（含已装箱、已送达）——件数只数合格的；</li>
 *   <li>众筹捐款：那笔捐款「已到账」（退款后不再够格）。</li>
 * </ul>
 *
 * @author hengde
 */
@Service
public class DonateCertificateQueryService {

    /** 合格的物资：核对合格之后的装箱、送达仍是合格的那一件。 */
    private static final List<Integer> QUALIFIED_ITEM =
            List.of(DonateFlow.ITEM_QUALIFIED, DonateFlow.ITEM_PACKED, DonateFlow.ITEM_DELIVERED);

    /**
     * 出证所需的事实。
     *
     * @param volunteerId 证书归属人
     * @param category    分类（{@link DonateCertRefs} 的 CATEGORY_*）
     * @param sourceTitle 来源名（项目 / 心愿 / 捐书活动的标题，列表展示用）
     * @param statement   证书正文里「您于某日」之后的那半句，如「认领并实现了微心愿「…」」
     * @param time        那件事发生的时间（结对成功 / 心愿实现 / 核对 / 到账）
     */
    public record Subject(Long volunteerId, String category, String sourceTitle, String statement,
                          LocalDateTime time) {
    }

    private DonatePairQueryService pairQueryService;
    private DonateWishClaimMapper claimMapper;
    private DonateWishMapper wishMapper;
    private DonateShipmentMapper shipmentMapper;
    private DonateItemMapper itemMapper;
    private DonateCampaignMapper campaignMapper;
    private DonateCrowdfundMapper crowdfundMapper;
    private DonateDonationMapper donationMapper;

    @Autowired
    public void setPairQueryService(DonatePairQueryService pairQueryService) {
        this.pairQueryService = pairQueryService;
    }

    @Autowired
    public void setClaimMapper(DonateWishClaimMapper claimMapper) {
        this.claimMapper = claimMapper;
    }

    @Autowired
    public void setWishMapper(DonateWishMapper wishMapper) {
        this.wishMapper = wishMapper;
    }

    @Autowired
    public void setShipmentMapper(DonateShipmentMapper shipmentMapper) {
        this.shipmentMapper = shipmentMapper;
    }

    @Autowired
    public void setItemMapper(DonateItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    @Autowired
    public void setCampaignMapper(DonateCampaignMapper campaignMapper) {
        this.campaignMapper = campaignMapper;
    }

    @Autowired
    public void setCrowdfundMapper(DonateCrowdfundMapper crowdfundMapper) {
        this.crowdfundMapper = crowdfundMapper;
    }

    @Autowired
    public void setDonationMapper(DonateDonationMapper donationMapper) {
        this.donationMapper = donationMapper;
    }

    /** 取出证事实；不够格（或来源键不认识）返回 null。 */
    public Subject find(String bizRef) {
        String category = DonateCertRefs.categoryOf(bizRef);
        if (category == null) {
            return null;
        }
        if (bizRef.startsWith(DonateCertRefs.PAIR)) {
            return pair(DonateCertRefs.idOf(bizRef, DonateCertRefs.PAIR));
        }
        if (bizRef.startsWith(DonateCertRefs.WISH)) {
            return wish(DonateCertRefs.idOf(bizRef, DonateCertRefs.WISH));
        }
        if (bizRef.startsWith(DonateCertRefs.BOOK)) {
            return goods(DonateCertRefs.idOf(bizRef, DonateCertRefs.BOOK), DonateFlow.BIZ_BOOK_CAMPAIGN);
        }
        if (bizRef.startsWith(DonateCertRefs.CROWDFUND_GOODS)) {
            return goods(DonateCertRefs.idOf(bizRef, DonateCertRefs.CROWDFUND_GOODS), DonateFlow.BIZ_CROWDFUND_GOODS);
        }
        return money(DonateCertRefs.idOf(bizRef, DonateCertRefs.CROWDFUND_MONEY));
    }

    private Subject pair(Long pairRecordId) {
        DonatePairQueryService.PairCertificateSubject s = pairQueryService.findEstablishedSubject(pairRecordId);
        if (s == null) {
            return null;
        }
        String type = s.projectTypeLabel() == null || s.projectTypeLabel().isBlank() ? "" : "（" + s.projectTypeLabel() + "）";
        return new Subject(s.volunteerId(), DonateCertRefs.CATEGORY_PAIR, s.projectTitle(),
                "与「" + s.projectTitle() + "」" + type + "结对成功，结成帮扶对子", s.establishedTime());
    }

    private Subject wish(Long claimId) {
        DonateWishClaim c = claimId == null ? null : claimMapper.selectById(claimId);
        if (c == null || !Objects.equals(c.getStatus(), WishFlow.CLAIM_REALIZED)) {
            return null;
        }
        DonateWish w = wishMapper.selectById(c.getWishId());
        if (w == null) {
            return null;
        }
        return new Subject(c.getVolunteerId(), DonateCertRefs.CATEGORY_WISH, w.getTitle(),
                "认领并实现了微心愿「" + w.getTitle() + "」", c.getRealizeTime());
    }

    private Subject goods(Long shipmentId, int bizType) {
        DonateShipment s = shipmentId == null ? null : shipmentMapper.selectById(shipmentId);
        if (s == null || !Objects.equals(s.getBizType(), bizType)
                || !Objects.equals(s.getStatus(), DonateFlow.SHIPMENT_CHECKED) || s.getDonorVolunteerId() == null) {
            return null;
        }
        int pieces = itemMapper.selectList(Wrappers.<DonateItem>lambdaQuery()
                        .select(DonateItem::getQuantity)
                        .eq(DonateItem::getShipmentId, shipmentId)
                        .in(DonateItem::getStatus, QUALIFIED_ITEM))
                .stream().mapToInt(i -> i.getQuantity() == null ? 1 : i.getQuantity()).sum();
        if (pieces <= 0) {
            return null;
        }
        if (bizType == DonateFlow.BIZ_BOOK_CAMPAIGN) {
            DonateCampaign c = campaignMapper.selectById(s.getBizId());
            if (c == null) {
                return null;
            }
            return new Subject(s.getDonorVolunteerId(), DonateCertRefs.CATEGORY_BOOK, c.getTitle(),
                    "向公益捐书活动「" + c.getTitle() + "」捐赠图书等物资共 " + pieces + " 件（经核对合格）", s.getCheckTime());
        }
        DonateCrowdfund cf = crowdfundMapper.selectById(s.getBizId());
        if (cf == null) {
            return null;
        }
        return new Subject(s.getDonorVolunteerId(), DonateCertRefs.CATEGORY_CROWDFUND, cf.getTitle(),
                "向众筹项目「" + cf.getTitle() + "」捐赠物资共 " + pieces + " 件（经核对合格）", s.getCheckTime());
    }

    private Subject money(Long donationId) {
        DonateDonation d = donationId == null ? null : donationMapper.selectById(donationId);
        if (d == null || !Objects.equals(d.getBizType(), DonationFlow.BIZ_CROWDFUND)
                || !Objects.equals(d.getStatus(), DonationFlow.PAID)) {
            return null;
        }
        DonateCrowdfund cf = crowdfundMapper.selectById(d.getProjectId());
        if (cf == null) {
            return null;
        }
        return new Subject(d.getVolunteerId(), DonateCertRefs.CATEGORY_CROWDFUND, cf.getTitle(),
                "向众筹项目「" + cf.getTitle() + "」捐款人民币 " + d.getAmount().stripTrailingZeros().toPlainString() + " 元",
                d.getPaidTime());
    }

    /**
     * 批量取来源名（证书列表的「来源」一列），一类一次查完，不按行循环。不认识 / 查不到的键不出现在结果里。
     *
     * @return 来源键 → 来源名
     */
    public Map<String, String> sourceTitles(Collection<String> bizRefs) {
        if (bizRefs == null || bizRefs.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        // 结对：沿用既有的批量换名
        Map<String, Long> pairIds = idsOf(bizRefs, DonateCertRefs.PAIR);
        if (!pairIds.isEmpty()) {
            Map<Long, String> t = pairQueryService.projectTitlesByPairIds(pairIds.values());
            pairIds.forEach((ref, id) -> putIfPresent(out, ref, t.get(id)));
        }
        // 微心愿：认领 → 心愿
        Map<String, Long> claimIds = idsOf(bizRefs, DonateCertRefs.WISH);
        if (!claimIds.isEmpty()) {
            Map<Long, Long> wishOfClaim = claimMapper.selectList(Wrappers.<DonateWishClaim>lambdaQuery()
                            .select(DonateWishClaim::getId, DonateWishClaim::getWishId)
                            .in(DonateWishClaim::getId, claimIds.values()))
                    .stream().collect(Collectors.toMap(DonateWishClaim::getId, DonateWishClaim::getWishId, (a, b) -> a));
            Map<Long, String> wishTitle = titles(wishMapper.selectBatchIds(new ArrayList<>(wishOfClaim.values())),
                    DonateWish::getId, DonateWish::getTitle);
            claimIds.forEach((ref, id) -> putIfPresent(out, ref, wishTitle.get(wishOfClaim.get(id))));
        }
        // 捐书 / 众筹捐物：运单 → 活动 / 项目
        Map<String, Long> bookShip = idsOf(bizRefs, DonateCertRefs.BOOK);
        Map<String, Long> cfShip = idsOf(bizRefs, DonateCertRefs.CROWDFUND_GOODS);
        if (!bookShip.isEmpty() || !cfShip.isEmpty()) {
            List<Long> ids = new ArrayList<>(bookShip.values());
            ids.addAll(cfShip.values());
            Map<Long, Long> bizIdOfShip = shipmentMapper.selectList(Wrappers.<DonateShipment>lambdaQuery()
                            .select(DonateShipment::getId, DonateShipment::getBizId)
                            .in(DonateShipment::getId, ids))
                    .stream().collect(Collectors.toMap(DonateShipment::getId, DonateShipment::getBizId, (a, b) -> a));
            if (!bookShip.isEmpty()) {
                Map<Long, String> t = titles(campaignMapper.selectBatchIds(bookShip.values().stream()
                                .map(bizIdOfShip::get).filter(Objects::nonNull).distinct().toList()),
                        DonateCampaign::getId, DonateCampaign::getTitle);
                bookShip.forEach((ref, id) -> putIfPresent(out, ref, t.get(bizIdOfShip.get(id))));
            }
            if (!cfShip.isEmpty()) {
                Map<Long, String> t = crowdfundTitles(cfShip.values().stream()
                        .map(bizIdOfShip::get).filter(Objects::nonNull).distinct().toList());
                cfShip.forEach((ref, id) -> putIfPresent(out, ref, t.get(bizIdOfShip.get(id))));
            }
        }
        // 众筹捐款：捐款 → 项目
        Map<String, Long> donIds = idsOf(bizRefs, DonateCertRefs.CROWDFUND_MONEY);
        if (!donIds.isEmpty()) {
            Map<Long, Long> projectOf = donationMapper.selectList(Wrappers.<DonateDonation>lambdaQuery()
                            .select(DonateDonation::getId, DonateDonation::getProjectId)
                            .in(DonateDonation::getId, donIds.values()))
                    .stream().collect(Collectors.toMap(DonateDonation::getId, DonateDonation::getProjectId, (a, b) -> a));
            Map<Long, String> t = crowdfundTitles(projectOf.values().stream().distinct().toList());
            donIds.forEach((ref, id) -> putIfPresent(out, ref, t.get(projectOf.get(id))));
        }
        return out;
    }

    private Map<Long, String> crowdfundTitles(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return titles(crowdfundMapper.selectBatchIds(ids), DonateCrowdfund::getId, DonateCrowdfund::getTitle);
    }

    private static <T> Map<Long, String> titles(List<T> rows, Function<T, Long> id, Function<T, String> title) {
        Map<Long, String> m = new HashMap<>();
        for (T r : rows) {
            if (title.apply(r) != null) {
                m.put(id.apply(r), title.apply(r));
            }
        }
        return m;
    }

    private static Map<String, Long> idsOf(Collection<String> refs, String prefix) {
        Map<String, Long> m = new HashMap<>();
        for (String r : refs) {
            Long id = DonateCertRefs.idOf(r, prefix);
            if (id != null) {
                m.put(r, id);
            }
        }
        return m;
    }

    private static void putIfPresent(Map<String, String> out, String key, String value) {
        if (value != null) {
            out.put(key, value);
        }
    }
}
