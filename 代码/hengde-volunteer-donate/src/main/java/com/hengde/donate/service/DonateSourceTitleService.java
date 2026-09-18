package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.dao.DonateCampaignMapper;
import com.hengde.donate.dao.DonatePairMappers.DonateCrowdfundMapper;
import com.hengde.donate.entity.DonateCrowdfund;
import com.hengde.donate.dao.DonateWishClaimMapper;
import com.hengde.donate.dao.DonateWishMapper;
import com.hengde.donate.entity.DonateCampaign;
import com.hengde.donate.entity.DonateWish;
import com.hengde.donate.entity.DonateWishClaim;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 物资流转记录的「来源名」：一个运单 / 一件物资挂在哪个捐书活动或哪个微心愿下。
 *
 * <p>运单、物资、箱子三张表用 {@code biz_type + biz_id} 指向来源，而 {@code biz_id} 在不同来源下指向不同的表
 * （捐书是活动 id，微心愿是<b>认领</b> id）。只拿 biz_id 去活动表查名字，会把微心愿的认领 id 当成活动 id——
 * 撞上同号的活动就显示成别人的活动名，而且没有任何报错。所以来源名一律经这里按类型分别查。</p>
 *
 * <p>只依赖 mapper、不依赖别的 service：运单与物资两个服务都用它，依赖 service 容易绕出循环。</p>
 *
 * @author hengde
 */
@Service
public class DonateSourceTitleService {

    /** 来源键：类型 + 该类型下的 id。 */
    public record Source(int bizType, long bizId) {
        public static Source of(Integer bizType, Long bizId) {
            return new Source(bizType == null ? 0 : bizType, bizId == null ? 0L : bizId);
        }
    }

    private DonateCampaignMapper campaignMapper;
    private DonateWishClaimMapper claimMapper;
    private DonateWishMapper wishMapper;
    private DonateCrowdfundMapper crowdfundMapper;

    @Autowired
    public void setCrowdfundMapper(DonateCrowdfundMapper crowdfundMapper) {
        this.crowdfundMapper = crowdfundMapper;
    }

    @Autowired
    public void setCampaignMapper(DonateCampaignMapper campaignMapper) {
        this.campaignMapper = campaignMapper;
    }

    @Autowired
    public void setClaimMapper(DonateWishClaimMapper claimMapper) {
        this.claimMapper = claimMapper;
    }

    @Autowired
    public void setWishMapper(DonateWishMapper wishMapper) {
        this.wishMapper = wishMapper;
    }

    /** 批量取来源名；查不到的来源不在返回里。捐书活动、微心愿各一次批量查。 */
    public Map<Source, String> titlesOf(Collection<Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return Map.of();
        }
        Set<Long> campaignIds = idsOf(sources, DonateFlow.BIZ_BOOK_CAMPAIGN);
        Set<Long> claimIds = idsOf(sources, DonateFlow.BIZ_WISH);
        Set<Long> crowdfundIds = idsOf(sources, DonateFlow.BIZ_CROWDFUND_GOODS);
        Map<Source, String> out = new HashMap<>();
        if (!crowdfundIds.isEmpty()) {
            // 「项目众筹 · 修一条路」——与「微心愿 HDW… · 书包」同形，列表里一眼分得清来源类型
            crowdfundMapper.selectList(Wrappers.<DonateCrowdfund>lambdaQuery()
                            .select(DonateCrowdfund::getId, DonateCrowdfund::getTitle)
                            .in(DonateCrowdfund::getId, crowdfundIds))
                    .forEach(c -> out.put(new Source(DonateFlow.BIZ_CROWDFUND_GOODS, c.getId()), "项目众筹 · " + c.getTitle()));
        }
        if (!campaignIds.isEmpty()) {
            campaignMapper.selectList(Wrappers.<DonateCampaign>lambdaQuery()
                            .select(DonateCampaign::getId, DonateCampaign::getTitle)
                            .in(DonateCampaign::getId, campaignIds))
                    .forEach(c -> out.put(new Source(DonateFlow.BIZ_BOOK_CAMPAIGN, c.getId()), c.getTitle()));
        }
        if (!claimIds.isEmpty()) {
            List<DonateWishClaim> claims = claimMapper.selectList(Wrappers.<DonateWishClaim>lambdaQuery()
                    .select(DonateWishClaim::getId, DonateWishClaim::getWishId)
                    .in(DonateWishClaim::getId, claimIds));
            Set<Long> wishIds = claims.stream().map(DonateWishClaim::getWishId).collect(Collectors.toSet());
            Map<Long, DonateWish> wishes = wishIds.isEmpty() ? Map.of()
                    : wishMapper.selectList(Wrappers.<DonateWish>lambdaQuery()
                            .select(DonateWish::getId, DonateWish::getWishNo, DonateWish::getTitle)
                            .in(DonateWish::getId, wishIds))
                    .stream().collect(Collectors.toMap(DonateWish::getId, Function.identity()));
            for (DonateWishClaim c : claims) {
                DonateWish w = wishes.get(c.getWishId());
                if (w != null) {
                    out.put(new Source(DonateFlow.BIZ_WISH, c.getId()), wishTitle(w));
                }
            }
        }
        return out;
    }

    /** 某个认领对应的心愿（全字段，含密文）；认领或心愿不存在返回 null。 */
    public DonateWish wishOfClaim(Long claimId) {
        DonateWishClaim c = claimId == null ? null : claimMapper.selectById(claimId);
        return c == null ? null : wishMapper.selectById(c.getWishId());
    }

    /** 「微心愿 HDW… · 一个新书包」——同名的心愿不少，带上编号才分得清。 */
    static String wishTitle(DonateWish w) {
        return "微心愿 " + w.getWishNo() + " · " + w.getTitle();
    }

    private static Set<Long> idsOf(Collection<Source> sources, int bizType) {
        return sources.stream().filter(s -> s.bizType() == bizType).map(Source::bizId).collect(Collectors.toSet());
    }
}
