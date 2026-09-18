package com.hengde.donate.service;

import com.hengde.common.exception.BusinessException;
import com.hengde.donate.config.DonateLogisticsProperties;
import com.hengde.donate.constant.DonateFlow;
import com.hengde.donate.constant.ExpressCompany;
import com.hengde.donate.dao.DonateShipmentMapper;
import com.hengde.donate.entity.DonateShipment;
import com.hengde.donate.logistics.Kuaidi100PushClient;
import com.hengde.donate.logistics.LogisticsClient;
import com.hengde.donate.logistics.LogisticsPushClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 物流订阅推送（V3 物流推送批）：向快递100 订阅在途运单，接收它推过来的轨迹。
 *
 * <p><b>订阅是付费项</b>，所以这里的每一条规矩都是在防「同一张运单订两遍」或「订了却收不到」：</p>
 * <ul>
 *   <li><b>先在库里占住、再发请求</b>（{@code claimSubscribe} 影响行数为 1 才调快递100）——多实例、任务重叠都只订一次；</li>
 *   <li><b>「重复订阅」算成功</b>——上一次其实订上了、只是应答丢了；</li>
 *   <li><b>被明确拒绝的不重试，失败次数用尽就放弃</b>，两种都<b>交还给轮询</b>（捐书批那条查询链路照常兜底）；</li>
 *   <li><b>推送验签用每单自己的 salt</b>，回调地址里带运单 id，且单号必须与运单对得上；</li>
 *   <li><b>乱序与重复推送</b>由 UPDATE 的 WHERE 挡（较早的推送晚到不覆盖较新的快照）。</li>
 * </ul>
 *
 * <p><b>推送验签在这里、不在控制器</b>（同 trade 批 D1 规矩 2）：控制器只把表单三个字段原样转过来。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class DonateLogisticsPushService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private DonateShipmentMapper shipmentMapper;
    private LogisticsPushClient pushClient;
    private DonateLogisticsProperties properties;
    private DonateTrackService trackService;

    @Autowired
    public void setShipmentMapper(DonateShipmentMapper shipmentMapper) {
        this.shipmentMapper = shipmentMapper;
    }

    @Autowired
    public void setPushClient(LogisticsPushClient pushClient) {
        this.pushClient = pushClient;
    }

    @Autowired
    public void setProperties(DonateLogisticsProperties properties) {
        this.properties = properties;
    }

    @Autowired
    public void setTrackService(DonateTrackService trackService) {
        this.trackService = trackService;
    }

    // ================= 订阅 =================

    /**
     * 定时入口：捞一批待订阅的在途运单逐个订阅。
     *
     * @return 本轮实际向快递100 发起订阅的单数
     */
    public int subscribeDue() {
        if (!pushClient.enabled()) {
            return 0;
        }
        DonateLogisticsProperties.Push push = properties.getPush();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime retryBefore = now.minusMinutes(push.getRetryIntervalMinutes());
        List<Long> ids = shipmentMapper.selectSubscribable(DonateFlow.SHIPMENT_SHIPPED, retryBefore,
                push.getSubscribeBatch());
        int sent = 0;
        for (Long id : ids) {
            try {
                if (subscribeOne(id, now, retryBefore)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                // 单条出错不影响这一轮的其它运单；它的尝试时间已记下，下一轮按重试间隔再来
                log.error("运单 {} 订阅物流推送出错", id, e);
            }
        }
        return sent;
    }

    private boolean subscribeOne(Long id, LocalDateTime now, LocalDateTime retryBefore) {
        DonateShipment s = shipmentMapper.selectById(id);
        if (s == null) {
            return false;
        }
        DonateLogisticsProperties.Push push = properties.getPush();
        if (!ExpressCompany.ofCode(s.getExpressCode()).orElse(ExpressCompany.OTHER).trackable()) {
            // 「其他」快递公司快递100 不认，订了也是被拒——不花这次请求，直接放弃
            shipmentMapper.markSubscribeFailed(id, "该快递公司不支持轨迹订阅", 1, push.getMaxAttempts(), now);
            return false;
        }
        if (s.getShipTime() != null && s.getShipTime().isBefore(now.minusDays(properties.getTrackMaxDays()))) {
            // 与轮询同一条终止条件：寄出太久还没到终态，多半是单号填错了，不该再为它付订阅费
            shipmentMapper.touchTrackQuery(id, now, 1);
            return false;
        }
        if (shipmentMapper.claimSubscribe(id, now, retryBefore, newSalt(), DonateFlow.SHIPMENT_SHIPPED) != 1) {
            return false;
        }
        String salt = shipmentMapper.selectSubscribeSalt(id);
        LogisticsPushClient.SubscribeResult r;
        try {
            r = pushClient.subscribe(s.getExpressCode(), s.getExpressNo(), trackService.phoneFor(s),
                    callbackUrlFor(id), salt);
        } catch (LogisticsClient.LogisticsException e) {
            log.warn("运单 {} 订阅物流推送失败（可重试）：{}", id, e.getMessage());
            shipmentMapper.markSubscribeFailed(id, cut(e.getMessage()), 0, push.getMaxAttempts(), now);
            return true;
        }
        if (r.accepted()) {
            shipmentMapper.markSubscribed(id, now);
        } else {
            log.warn("运单 {} 订阅物流推送被拒：{} {}（{}）", id, r.code(), r.message(),
                    r.permanent() ? "不再重试，交还轮询" : "稍后重试");
            shipmentMapper.markSubscribeFailed(id, cut(r.code() + " " + r.message()), r.permanent() ? 1 : 0,
                    push.getMaxAttempts(), now);
        }
        return true;
    }

    /** 后台重新订阅：只对「被中止 / 已放弃」且仍在途的运单。 */
    public void resubscribe(Long shipmentId, Long operatorId) {
        if (operatorId == null) {
            throw new BusinessException("操作人不能为空");
        }
        DonateShipment s = shipmentId == null ? null : shipmentMapper.selectById(shipmentId);
        if (s == null) {
            throw new BusinessException("运单不存在");
        }
        if (shipmentMapper.resetSubscribe(shipmentId, DonateFlow.SHIPMENT_SHIPPED, LocalDateTime.now()) != 1) {
            throw new BusinessException("只有「被中止」或「已放弃」且仍在途的运单可以重新订阅（当前："
                    + DonateFlow.subscribeLabel(s.getSubscribeStatus()) + "，运单"
                    + DonateFlow.shipmentLabel(s.getStatus()) + "）");
        }
        log.info("运单 {} 由管理员 {} 重新发起物流订阅", shipmentId, operatorId);
    }

    String callbackUrlFor(Long shipmentId) {
        String base = properties.getPush().getCallbackUrl();
        return base + (base.contains("?") ? "&" : "?") + "sid=" + shipmentId;
    }

    // ================= 推送回调 =================

    /**
     * 处理一条快递100 推送。
     *
     * <p><b>应答语义</b>：处理完（含重复推送、乱序到达被忽略）回成功，让快递100 别再重推；
     * <b>验签失败、单号对不上回失败</b>——那是伪造或者串单，藏起来最糟。
     * 运单不存在 / 没订阅过的也回成功（记 WARN）：它没有 salt、验不了签，也改不了任何东西，
     * 让快递100 一直重推只会把日志刷满。</p>
     *
     * @param shipmentId 回调地址里的 {@code sid}
     * @param param      表单里的 {@code param}（原文，验签就算它）
     * @param sign       表单里的 {@code sign}
     */
    public Ack handleCallback(Long shipmentId, String param, String sign) {
        if (shipmentId == null) {
            log.warn("快递100 推送缺少运单 id，忽略");
            return Ack.ok("忽略");
        }
        DonateShipment s = shipmentMapper.selectById(shipmentId);
        String salt = s == null ? null : shipmentMapper.selectSubscribeSalt(shipmentId);
        if (s == null || !StringUtils.hasText(salt)) {
            log.warn("快递100 推送指向不存在或未订阅过的运单 {}，忽略", shipmentId);
            return Ack.ok("忽略");
        }
        if (!StringUtils.hasText(param)
                || !Kuaidi100PushClient.signMatches(Kuaidi100PushClient.callbackSign(param, salt), sign)) {
            log.warn("快递100 推送验签失败 shipmentId={}", shipmentId);
            return Ack.fail("签名错误");
        }
        Kuaidi100PushClient.PushedCallback cb;
        try {
            cb = Kuaidi100PushClient.parseCallback(param);
        } catch (LogisticsClient.LogisticsException e) {
            log.warn("快递100 推送报文无法解析 shipmentId={}：{}", shipmentId, e.getMessage());
            return Ack.fail("报文格式错误");
        }
        if (StringUtils.hasText(cb.number()) && !sameNumber(cb.number(), s.getExpressNo())) {
            // 签名对、单号不对：salt 串了或回调地址被改过，不能把别人的轨迹写到这张运单上
            log.error("快递100 推送单号与运单不符 shipmentId={} 运单={} 推送={}", shipmentId, s.getExpressNo(),
                    cb.number());
            return Ack.fail("单号不符");
        }
        LocalDateTime now = LocalDateTime.now();
        if (cb.result() != null) {
            LogisticsClient.TrackNode latest = cb.result().latest();
            boolean terminal = cb.result().state() != null
                    && DonateTrackService.TERMINAL_STATES.contains(cb.result().state());
            boolean stopByStatus = s.getStatus() != null && s.getStatus() != DonateFlow.SHIPMENT_SHIPPED;
            int rows = shipmentMapper.applyPushedTrack(shipmentId, cb.result().state(),
                    latest == null ? null : DonateTrackService.truncate(latest.context()),
                    latest == null ? null : latest.time(), DonateTrackService.toJson(cb.result().nodes()), now,
                    (terminal || stopByStatus) ? 1 : 0);
            if (rows == 0) {
                log.info("快递100 推送晚于已有快照到达，忽略轨迹 shipmentId={}", shipmentId);
            }
        }
        switch (cb.status()) {
            case "shutdown" -> shipmentMapper.markPushFinished(shipmentId, now);
            case "abort" -> shipmentMapper.markPushAborted(shipmentId,
                    cut("快递100 中止：" + cb.message()), now);
            default -> shipmentMapper.markPushPolling(shipmentId, now);
        }
        return Ack.ok("成功");
    }

    private static boolean sameNumber(String a, String b) {
        return b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static String newSalt() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String cut(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 255 ? s.substring(0, 255) : s;
    }

    /** 给快递100 的应答。 */
    public record Ack(boolean success, String message) {

        static Ack ok(String message) {
            return new Ack(true, message);
        }

        static Ack fail(String message) {
            return new Ack(false, message);
        }
    }
}
