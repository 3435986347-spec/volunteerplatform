package com.hengde.donate.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengde.common.exception.BusinessException;
import com.hengde.donate.config.DonateLogisticsProperties;
import com.hengde.donate.config.DonateWishProperties;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.constant.ExpressCompany;
import com.hengde.donate.dao.DonateShipmentMapper;
import com.hengde.donate.entity.DonateCampaign;
import com.hengde.donate.entity.DonateShipment;
import com.hengde.donate.logistics.LogisticsClient;
import com.hengde.donate.logistics.LogisticsPushClient;
import com.hengde.donate.vo.DonateFlowVOs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 物流轨迹（Row 17「物流轨迹【有快递100的API】」）——<b>本批只做查询</b>（清单 ⑧），订阅推送属物流推送批。
 *
 * <p>三条规矩：</p>
 * <ul>
 *   <li><b>快照落库</b>：每次查询把结果写进运单的 track_* 列。外部不可用时返回旧快照并标 {@code stale}，
 *       页面仍能看——物流轨迹是附加信息，我们自己的流转轨迹不依赖它；</li>
 *   <li><b>不每看一次就查一次</b>：快照未过 {@code refresh-minutes} 直接返回（快递100 按次计费）；</li>
 *   <li><b>终态停止</b>：签收 / 退签 / 退回 / 拒签，或我们已确认到货、或寄出超过 {@code track-max-days} 天，
 *       置 {@code track_done = 1} 退出轮询集合——否则扫描集合随历史单量单调增长，一个填错的单号会被查一辈子。</li>
 * </ul>
 *
 * @author hengde
 */
@Slf4j
@Service
public class DonateTrackService {

    /** 快递100 物流状态里的终态：3 签收 / 4 退签 / 6 退回 / 14 拒签。 */
    static final Set<Integer> TERMINAL_STATES = Set.of(3, 4, 6, 14);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DonateShipmentMapper shipmentMapper;
    private LogisticsClient logisticsClient;
    private DonateLogisticsProperties properties;
    private BookCampaignService campaignService;
    private LogisticsPushClient pushClient;
    private DonateWishProperties wishProperties;
    private com.hengde.donate.dao.DonatePairMappers.DonateCrowdfundMapper crowdfundMapper;

    @Autowired
    public void setCrowdfundMapper(com.hengde.donate.dao.DonatePairMappers.DonateCrowdfundMapper crowdfundMapper) {
        this.crowdfundMapper = crowdfundMapper;
    }

    @Autowired
    public void setPushClient(LogisticsPushClient pushClient) {
        this.pushClient = pushClient;
    }

    @Autowired
    public void setWishProperties(DonateWishProperties wishProperties) {
        this.wishProperties = wishProperties;
    }

    @Autowired
    public void setShipmentMapper(DonateShipmentMapper shipmentMapper) {
        this.shipmentMapper = shipmentMapper;
    }

    @Autowired
    public void setLogisticsClient(LogisticsClient logisticsClient) {
        this.logisticsClient = logisticsClient;
    }

    @Autowired
    public void setProperties(DonateLogisticsProperties properties) {
        this.properties = properties;
    }

    @Autowired
    public void setCampaignService(BookCampaignService campaignService) {
        this.campaignService = campaignService;
    }

    /** 捐赠人看自己运单的物流。不是本人的与不存在同一句话。 */
    public DonateFlowVOs.Track trackForDonor(Long shipmentId, Long volunteerId) {
        DonateShipment s = shipmentId == null ? null : shipmentMapper.selectById(shipmentId);
        if (s == null || !s.getDonorVolunteerId().equals(volunteerId)) {
            throw new BusinessException("运单不存在");
        }
        return refreshIfStale(s, false);
    }

    /** 后台看物流，可强制刷新（仍受「已到终态不再查」约束）。 */
    public DonateFlowVOs.Track trackForAdmin(Long shipmentId, boolean force) {
        DonateShipment s = shipmentId == null ? null : shipmentMapper.selectById(shipmentId);
        if (s == null) {
            throw new BusinessException("运单不存在");
        }
        return refreshIfStale(s, force);
    }

    /**
     * 定时轮询：捞一批「未到终态、仍是已寄出、距上次查询超过间隔」的运单逐个查。
     *
     * @return 本轮实际发起查询的单数
     */
    public int pollDue() {
        if (!properties.isPollEnabled() || !logisticsClient.enabled()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        // 订阅推送开着时，只轮询「订阅走不通」的那些（被中止 / 已放弃）——订上了的由推送保持快照，再查一遍是重复付费
        List<Long> ids = shipmentMapper.selectPollable(DonateFlow.SHIPMENT_SHIPPED,
                now.minusMinutes(properties.getPollIntervalMinutes()), pushClient.enabled() ? 1 : 0,
                properties.getPollBatch());
        int queried = 0;
        for (Long id : ids) {
            DonateShipment s = shipmentMapper.selectById(id);
            if (s == null) {
                continue;
            }
            if (s.getShipTime() != null && s.getShipTime().isBefore(now.minusDays(properties.getTrackMaxDays()))) {
                shipmentMapper.touchTrackQuery(id, now, 1);
                log.info("运单 {} 寄出超过 {} 天仍未到终态，停止轮询", id, properties.getTrackMaxDays());
                continue;
            }
            query(s, now);
            queried++;
        }
        return queried;
    }

    // ================= 内部 =================

    private DonateFlowVOs.Track refreshIfStale(DonateShipment s, boolean force) {
        LocalDateTime now = LocalDateTime.now();
        ExpressCompany company = ExpressCompany.ofCode(s.getExpressCode()).orElse(ExpressCompany.OTHER);
        if (!company.trackable()) {
            return snapshot(s, false, false, "该快递公司暂不支持查询物流轨迹");
        }
        if (!logisticsClient.enabled()) {
            return snapshot(s, false, false, "物流查询未开通");
        }
        boolean fresh = s.getTrackQueryTime() != null
                && s.getTrackQueryTime().isAfter(now.minusMinutes(properties.getRefreshMinutes()));
        boolean done = s.getTrackDone() != null && s.getTrackDone() == 1 && s.getTrackQueryTime() != null;
        // 订阅中的运单由快递100 推送保持快照：志愿者每打开一次就去按次查询，等于订阅与查询各付一份钱。
        // 后台显式刷新（force）不受限——推送卡住时那是唯一的手动出口
        boolean pushed = s.getSubscribeStatus() != null && s.getSubscribeStatus() == DonateFlow.SUBSCRIBE_ACTIVE
                && pushClient.enabled();
        if (done || ((fresh || pushed) && !force)) {
            return snapshot(s, true, false, null);
        }
        boolean ok = query(s, now);
        DonateShipment reloaded = shipmentMapper.selectById(s.getId());
        return snapshot(reloaded, true, !ok, ok ? null : "物流查询暂时不可用，显示的是上次的结果");
    }

    /** 查一次并落快照；失败只记查询时间、保留旧快照。返回是否成功。 */
    private boolean query(DonateShipment s, LocalDateTime now) {
        String phone = phoneFor(s);
        boolean stopByStatus = s.getStatus() != null && s.getStatus() != DonateFlow.SHIPMENT_SHIPPED;
        try {
            LogisticsClient.TrackResult r = logisticsClient.query(s.getExpressCode(), s.getExpressNo(), phone);
            LogisticsClient.TrackNode latest = r.latest();
            boolean terminal = r.state() != null && TERMINAL_STATES.contains(r.state());
            shipmentMapper.updateTrack(s.getId(), r.state(), latest == null ? null : truncate(latest.context()),
                    latest == null ? null : latest.time(), toJson(r.nodes()), now, (terminal || stopByStatus) ? 1 : 0);
            return true;
        } catch (LogisticsClient.LogisticsException e) {
            log.warn("运单 {} 物流查询失败：{}", s.getId(), e.getMessage());
            shipmentMapper.touchTrackQuery(s.getId(), now, stopByStatus ? 1 : 0);
            return false;
        }
    }

    /**
     * 顺丰等要求收 / 寄件人电话。寄往协会的包裹，收件电话就是预留的那个：
     * 捐书活动取活动上的，微心愿取微心愿配置里的（物资一律寄到办公室）。
     *
     * <p>⚠️ 微心愿这一支是物流推送批补的——此前查询也只认捐书活动，微心愿的顺丰包裹传的是空电话，查不到轨迹。</p>
     */
    public String phoneFor(DonateShipment s) {
        if (s.getBizType() == null) {
            return null;
        }
        if (s.getBizType() == DonateFlow.BIZ_BOOK_CAMPAIGN) {
            DonateCampaign c = campaignService.require(s.getBizId());
            return c.getRecvPhone();
        }
        if (s.getBizType() == DonateFlow.BIZ_WISH) {
            return StringUtils.hasText(wishProperties.getRecvPhone()) ? wishProperties.getRecvPhone() : null;
        }
        if (s.getBizType() == DonateFlow.BIZ_CROWDFUND_GOODS) {
            com.hengde.donate.entity.DonateCrowdfund c = crowdfundMapper.selectById(s.getBizId());
            return c == null || !StringUtils.hasText(c.getRecvPhone()) ? null : c.getRecvPhone();
        }
        return null;
    }

    private DonateFlowVOs.Track snapshot(DonateShipment s, boolean available, boolean stale, String message) {
        DonateFlowVOs.Track t = new DonateFlowVOs.Track();
        t.setAvailable(available);
        t.setStale(stale);
        t.setMessage(message);
        t.setState(s.getTrackState());
        t.setStateLabel(stateLabel(s.getTrackState()));
        t.setLastContext(s.getTrackLastContext());
        t.setLastTime(s.getTrackLastTime());
        t.setQueryTime(s.getTrackQueryTime());
        String json = shipmentMapper.selectTrackJson(s.getId());
        if (StringUtils.hasText(json)) {
            try {
                List<Map<String, String>> nodes = JSON.readValue(json, new TypeReference<>() {
                });
                for (Map<String, String> n : nodes) {
                    DonateFlowVOs.TrackNodeVO v = new DonateFlowVOs.TrackNodeVO();
                    v.setTime(StringUtils.hasText(n.get("time")) ? LocalDateTime.parse(n.get("time"), TIME) : null);
                    v.setContext(n.get("context"));
                    t.getNodes().add(v);
                }
            } catch (Exception e) {
                log.warn("运单 {} 的轨迹快照无法解析，忽略", s.getId());
            }
        }
        return t;
    }

    /** 快照存<b>我们自己的</b>节点格式，不存快递100 的原始报文——换服务商时历史快照不必迁移。 */
    static String toJson(List<LogisticsClient.TrackNode> nodes) {
        List<Map<String, String>> list = new ArrayList<>();
        if (nodes != null) {
            for (LogisticsClient.TrackNode n : nodes) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("time", n.time() == null ? null : TIME.format(n.time()));
                m.put("context", n.context());
                list.add(m);
            }
        }
        try {
            return JSON.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    static String truncate(String s) {
        return s == null ? null : (s.length() > 512 ? s.substring(0, 512) : s);
    }

    /** 快递100 物流状态码的中文名。 */
    public static String stateLabel(Integer state) {
        if (state == null) {
            return "";
        }
        return switch (state) {
            case 0 -> "在途";
            case 1 -> "揽收";
            case 2 -> "疑难";
            case 3 -> "签收";
            case 4 -> "退签";
            case 5 -> "派件";
            case 6 -> "退回";
            case 7 -> "转投";
            case 8, 10, 11, 12 -> "清关";
            case 13 -> "清关异常";
            case 14 -> "拒签";
            default -> "其他";
        };
    }
}
