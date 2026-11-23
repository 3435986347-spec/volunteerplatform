package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.dao.DonateCampaignMapper;
import com.hengde.donate.dto.BookCampaignSaveDTO;
import com.hengde.donate.entity.DonateCampaign;
import com.hengde.donate.vo.DonateFlowVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 公益捐书活动（Row 17「首页展示本次活动名称、活动时间、状态、本次活动数据、分享、详情（文字+图片）」）。
 *
 * <p><b>「未开始 / 进行中 / 已结束」不落库、按时间现算</b>——与活动 {@code ActivityDisplayStatus} 同一个理由：
 * 落库就要有定时任务去翻状态，漏跑一次，一个已经结束的活动就还能报名。库里只存「草稿 / 已发布 / 已手动结束」。</p>
 *
 * <p><b>收件地址的格式是需求原文</b>（Row 17）：「收件人：【捐赠人姓名】，电话：后台预留，
 * 地址：后台预留+【捐赠人姓名】」——后台只填电话与地址前缀，姓名在志愿者端按当前登录人拼上。</p>
 *
 * @author hengde
 */
@Service
public class BookCampaignService {

    private DonateCampaignMapper campaignMapper;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setCampaignMapper(DonateCampaignMapper campaignMapper) {
        this.campaignMapper = campaignMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    // ================= 管理端 =================

    /** 新建活动，落草稿。 */
    public Long create(BookCampaignSaveDTO dto, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        validate(dto);
        DonateCampaign c = new DonateCampaign();
        c.setTitle(dto.getTitle().trim());
        c.setCoverUrl(dto.getCoverUrl());
        c.setDetail(dto.getDetail());
        c.setStartTime(dto.getStartTime());
        c.setEndTime(dto.getEndTime());
        c.setRecvPhone(dto.getRecvPhone());
        c.setRecvAddress(dto.getRecvAddress());
        c.setStatsDeadline(dto.getStatsDeadline());
        c.setStatus(DonateFlow.CAMPAIGN_DRAFT);
        c.setCreateBy(adminId);
        campaignMapper.insert(c);
        return c.getId();
    }

    /** 修改。显式 {@code .set()} 全部字段——统计截止时间要能被清空（updateById 跳过 null）。 */
    public void update(Long id, BookCampaignSaveDTO dto) {
        validate(dto);
        int rows = campaignMapper.update(null, Wrappers.<DonateCampaign>lambdaUpdate()
                .eq(DonateCampaign::getId, id)
                .set(DonateCampaign::getTitle, dto.getTitle().trim())
                .set(DonateCampaign::getCoverUrl, dto.getCoverUrl())
                .set(DonateCampaign::getDetail, dto.getDetail())
                .set(DonateCampaign::getStartTime, dto.getStartTime())
                .set(DonateCampaign::getEndTime, dto.getEndTime())
                .set(DonateCampaign::getRecvPhone, dto.getRecvPhone())
                .set(DonateCampaign::getRecvAddress, dto.getRecvAddress())
                .set(DonateCampaign::getStatsDeadline, dto.getStatsDeadline())
                .set(DonateCampaign::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("活动不存在");
        }
    }

    /**
     * 发布。<b>收件电话与地址必须先填</b>：志愿者报完名第一眼要看的就是寄到哪儿（Row 17 第 1 步
     * 「需要给捐赠人看得到收件地址」），发布一个没有地址的活动等于让人把东西寄向虚空。
     * 这条放在 WHERE 里一起判，免得「先查再发布」之间有人把地址清空。
     */
    public void publish(Long id) {
        int rows = campaignMapper.update(null, Wrappers.<DonateCampaign>lambdaUpdate()
                .eq(DonateCampaign::getId, id)
                .eq(DonateCampaign::getStatus, DonateFlow.CAMPAIGN_DRAFT)
                .isNotNull(DonateCampaign::getRecvPhone).ne(DonateCampaign::getRecvPhone, "")
                .isNotNull(DonateCampaign::getRecvAddress).ne(DonateCampaign::getRecvAddress, "")
                .set(DonateCampaign::getStatus, DonateFlow.CAMPAIGN_PUBLISHED)
                .set(DonateCampaign::getUpdateTime, LocalDateTime.now()));
        if (rows == 1) {
            return;
        }
        DonateCampaign c = id == null ? null : campaignMapper.selectById(id);
        if (c == null) {
            throw new BusinessException("活动不存在");
        }
        if (c.getStatus() != null && c.getStatus() != DonateFlow.CAMPAIGN_DRAFT) {
            throw new BusinessException("只有草稿可以发布");
        }
        throw new BusinessException("请先填写收件电话与收件地址再发布");
    }

    /** 手动结束（提前收尾）。到了结束时间不需要调它，展示状态按时间自动变。 */
    public void end(Long id) {
        int rows = campaignMapper.update(null, Wrappers.<DonateCampaign>lambdaUpdate()
                .eq(DonateCampaign::getId, id)
                .eq(DonateCampaign::getStatus, DonateFlow.CAMPAIGN_PUBLISHED)
                .set(DonateCampaign::getStatus, DonateFlow.CAMPAIGN_ENDED)
                .set(DonateCampaign::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("活动不存在或不在已发布状态");
        }
    }

    /**
     * 删除。<b>只能删草稿</b>——发布过的活动可能已经有人寄出了包裹，删掉活动会让那些运单
     * 失去来源（「我寄的书到底捐给了哪个活动」），条件写进 DELETE 的 WHERE。
     */
    public void delete(Long id) {
        int rows = campaignMapper.delete(Wrappers.<DonateCampaign>lambdaQuery()
                .eq(DonateCampaign::getId, id)
                .eq(DonateCampaign::getStatus, DonateFlow.CAMPAIGN_DRAFT));
        if (rows != 1) {
            throw new BusinessException("只有未发布的草稿可以删除；已发布的活动请用「结束」");
        }
    }

    public PageResult<DonateFlowVOs.Campaign> listForAdmin(PageQuery query, String keyword, Integer status) {
        IPage<DonateCampaign> page = campaignMapper.selectPage(query.toPage(), Wrappers.<DonateCampaign>lambdaQuery()
                .eq(status != null, DonateCampaign::getStatus, status)
                .like(StringUtils.hasText(keyword), DonateCampaign::getTitle, keyword)
                .orderByDesc(DonateCampaign::getId));
        return toPage(page, true);
    }

    public DonateFlowVOs.Campaign detailForAdmin(Long id) {
        DonateCampaign c = id == null ? null : campaignMapper.selectById(id);
        if (c == null) {
            throw new BusinessException("活动不存在");
        }
        DonateFlowVOs.Campaign vo = toVO(c, LocalDateTime.now(), true);
        vo.setDetail(c.getDetail());
        vo.setStats(stats(List.of(c.getId())).get(c.getId()));
        vo.setRecvPhone(c.getRecvPhone());
        vo.setRecvAddress(c.getRecvAddress());
        return vo;
    }

    // ================= 志愿者端 =================

    /** 已发布与已结束的活动（已结束的留着——「本次活动数据」本身就是给人看的成果）。草稿一律不可见。 */
    public PageResult<DonateFlowVOs.Campaign> listForVolunteer(PageQuery query) {
        IPage<DonateCampaign> page = campaignMapper.selectPage(query.toPage(), Wrappers.<DonateCampaign>lambdaQuery()
                .in(DonateCampaign::getStatus, DonateFlow.CAMPAIGN_PUBLISHED, DonateFlow.CAMPAIGN_ENDED)
                .orderByDesc(DonateCampaign::getStartTime)
                .orderByDesc(DonateCampaign::getId));
        return toPage(page, false);
    }

    /** 详情：带「本次活动数据」与按当前登录人拼好的收件信息。 */
    public DonateFlowVOs.Campaign detailForVolunteer(Long id, Long volunteerId) {
        DonateCampaign c = id == null ? null : campaignMapper.selectById(id);
        if (c == null || c.getStatus() == null || c.getStatus() == DonateFlow.CAMPAIGN_DRAFT) {
            throw new BusinessException("活动不存在");
        }
        DonateFlowVOs.Campaign vo = toVO(c, LocalDateTime.now(), false);
        vo.setDetail(c.getDetail());
        vo.setStats(stats(List.of(c.getId())).get(c.getId()));
        String name = volunteerQueryService.listNamesByIds(List.of(volunteerId)).get(volunteerId);
        vo.setRecvName(name);
        vo.setRecvPhone(c.getRecvPhone());
        vo.setRecvAddress(c.getRecvAddress() == null ? null
                : c.getRecvAddress() + (StringUtils.hasText(name) ? name : ""));
        return vo;
    }

    // ================= 供其他服务 =================

    /** 取活动（不限状态），不存在报错。 */
    public DonateCampaign require(Long id) {
        DonateCampaign c = id == null ? null : campaignMapper.selectById(id);
        if (c == null) {
            throw new BusinessException("活动不存在");
        }
        return c;
    }

    /** 此刻能报名：已发布且在活动时间内。 */
    public DonateCampaign requireOpen(Long id, LocalDateTime now) {
        DonateCampaign c = require(id);
        if (!isOpen(c, now)) {
            throw new BusinessException("该活动当前不在报名时间内");
        }
        return c;
    }

    public Map<Long, String> titlesOf(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> m = new HashMap<>();
        campaignMapper.selectList(Wrappers.<DonateCampaign>lambdaQuery()
                        .select(DonateCampaign::getId, DonateCampaign::getTitle)
                        .in(DonateCampaign::getId, ids))
                .forEach(c -> m.put(c.getId(), c.getTitle()));
        return m;
    }

    /** 「本次活动数据」，一批活动一次查完。口径写在 mapper 注释里。 */
    public Map<Long, DonateFlowVOs.CampaignStats> stats(Collection<Long> ids) {
        Map<Long, DonateFlowVOs.CampaignStats> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        Map<Long, LocalDateTime> deadlines = new HashMap<>();
        campaignMapper.selectList(Wrappers.<DonateCampaign>lambdaQuery()
                        .select(DonateCampaign::getId, DonateCampaign::getStatsDeadline)
                        .in(DonateCampaign::getId, ids))
                .forEach(c -> deadlines.put(c.getId(), c.getStatsDeadline()));
        for (Map<String, Object> row : campaignMapper.selectStats(ids, DonateFlow.BIZ_BOOK_CAMPAIGN,
                DonateFlow.SHIPMENT_CANCELLED, DonateFlow.SHIPMENT_ARRIVED, DonateFlow.SHIPMENT_CHECKED,
                DonateFlow.ITEM_QUALIFIED, DonateFlow.ITEM_PACKED, DonateFlow.ITEM_DELIVERED,
                DonateFlow.TYPE_BOOK, DonateFlow.TYPE_STATIONERY, DonateFlow.TYPE_SPORTS)) {
            Long id = num(row.get("campaignId"));
            DonateFlowVOs.CampaignStats s = new DonateFlowVOs.CampaignStats();
            s.setParticipants(num(row.get("participants")));
            s.setParcels(num(row.get("parcels")));
            s.setBooks(num(row.get("books")));
            s.setStationery(num(row.get("stationery")));
            s.setSports(num(row.get("sports")));
            s.setStatsDeadline(deadlines.get(id));
            result.put(id, s);
        }
        return result;
    }

    // ================= 内部 =================

    private static void validate(BookCampaignSaveDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getTitle())) {
            throw new BusinessException("请填写活动名称");
        }
        if (dto.getStartTime() == null || dto.getEndTime() == null) {
            throw new BusinessException("请填写活动时间");
        }
        if (!dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BusinessException("结束时间必须晚于开始时间");
        }
    }

    static boolean isOpen(DonateCampaign c, LocalDateTime now) {
        return c.getStatus() != null && c.getStatus() == DonateFlow.CAMPAIGN_PUBLISHED
                && !c.getStartTime().isAfter(now) && c.getEndTime().isAfter(now);
    }

    private PageResult<DonateFlowVOs.Campaign> toPage(IPage<DonateCampaign> page, boolean forAdmin) {
        LocalDateTime now = LocalDateTime.now();
        PageResult<DonateFlowVOs.Campaign> result = PageResult.of(page.convert(c -> toVO(c, now, forAdmin)));
        Map<Long, DonateFlowVOs.CampaignStats> stats = stats(result.getRecords().stream()
                .map(DonateFlowVOs.Campaign::getId).toList());
        result.getRecords().forEach(vo -> vo.setStats(stats.get(vo.getId())));
        return result;
    }

    private static DonateFlowVOs.Campaign toVO(DonateCampaign c, LocalDateTime now, boolean forAdmin) {
        DonateFlowVOs.Campaign vo = new DonateFlowVOs.Campaign();
        vo.setId(c.getId());
        vo.setTitle(c.getTitle());
        vo.setCoverUrl(c.getCoverUrl());
        vo.setStartTime(c.getStartTime());
        vo.setEndTime(c.getEndTime());
        vo.setStatus(c.getStatus());
        vo.setCreateTime(c.getCreateTime());
        Integer display = null;
        if (c.getStatus() != null && c.getStatus() != DonateFlow.CAMPAIGN_DRAFT) {
            if (c.getStatus() == DonateFlow.CAMPAIGN_ENDED || !c.getEndTime().isAfter(now)) {
                display = 2;
            } else if (c.getStartTime().isAfter(now)) {
                display = 0;
            } else {
                display = 1;
            }
        }
        vo.setDisplayStatus(display);
        vo.setDisplayLabel(display == null ? "草稿" : switch (display) {
            case 0 -> "未开始";
            case 1 -> "进行中";
            default -> "已结束";
        });
        vo.setOpen(isOpen(c, now));
        return vo;
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
