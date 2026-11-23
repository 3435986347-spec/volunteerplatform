package com.hengde.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.activity.service.ActivityRankingQueryService;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.entity.Volunteer;
import com.hengde.auth.service.MiniappCodeService;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.qrcode.QrCodeUtil;
import com.hengde.common.utils.MaskUtil;
import com.hengde.organization.biz.service.SquadQueryService;
import com.hengde.organization.exam.service.TempLeaderQueryService;
import com.hengde.user.config.VolunteerCardProperties;
import com.hengde.user.dao.VolunteerCardMapper;
import com.hengde.user.entity.VolunteerCard;
import com.hengde.user.vo.VolunteerCardVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 志愿者证（V4 志愿者证批，xlsx Row 26，V4规划 D11）：「类似身份证」的证件 + 小程序码，别人扫码看得到他的志愿者信息。
 *
 * <p><b>码里是随机令牌、不是志愿者 id</b>：核验端点免登录，裸 id 可以顺着枚举，等于把全体志愿者名册公开。令牌 24 字节随机数（32 个字符，小程序码 scene 的上限），
 * 第一次打开证件时生成，<b>本人可以重置</b>（证件截图外流时旧码当场失效）。未知令牌、已重置的旧令牌、账号已停用 / 注销的，核验时一律同一句「无效或已失效」。</p>
 *
 * <p><b>证件上的信息一律现算</b>：时长与次数与排行榜 / 勋章进度同一口径（{@link ActivityRankingQueryService#totalServiceMinutes} /
 * {@link ActivityRankingQueryService#totalAttendanceCount}），所属职务取临时负责人资格。公开核验只给 Q5 那几项：头像、姓名（只留姓）、编号、注册时间、累计时长、活动次数——不含手机号与学校。</p>
 *
 * <p><b>码图</b>：配了小程序 AppID 时生成小程序码（按令牌缓存在 Redis），否则回退普通二维码（内容为 H5 核验地址，没配地址时为 {@code hengde-volunteer-card:令牌}）。
 * 缓存与小程序码失败都只影响码的样式，不影响证件本身。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class VolunteerCardService {

    /** 普通二维码内容前缀（没配 H5 核验地址时；小程序自己的扫一扫据此识别）。 */
    public static final String QR_PREFIX = "hengde-volunteer-card:";
    static final String CODE_CACHE_PREFIX = "user:volunteer-card:code:";
    static final String INVALID = "志愿者证无效或已失效";
    private static final Pattern TOKEN_SHAPE = Pattern.compile("[A-Za-z0-9_-]{32}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private VolunteerCardMapper cardMapper;
    private VolunteerMapper volunteerMapper;
    private VolunteerQueryService volunteerQueryService;
    private ActivityRankingQueryService rankingQueryService;
    private TempLeaderQueryService tempLeaderQueryService;
    private SquadQueryService squadQueryService;
    private MiniappCodeService miniappCodeService;
    private VolunteerCardProperties properties;
    private RedissonClient redissonClient;

    @Autowired
    public void setCardMapper(VolunteerCardMapper cardMapper) {
        this.cardMapper = cardMapper;
    }

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setRankingQueryService(ActivityRankingQueryService rankingQueryService) {
        this.rankingQueryService = rankingQueryService;
    }

    @Autowired
    public void setTempLeaderQueryService(TempLeaderQueryService tempLeaderQueryService) {
        this.tempLeaderQueryService = tempLeaderQueryService;
    }

    @Autowired
    public void setSquadQueryService(SquadQueryService squadQueryService) {
        this.squadQueryService = squadQueryService;
    }

    @Autowired
    public void setMiniappCodeService(MiniappCodeService miniappCodeService) {
        this.miniappCodeService = miniappCodeService;
    }

    @Autowired
    public void setProperties(VolunteerCardProperties properties) {
        this.properties = properties;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** 我的志愿者证（第一次打开时发证）。 */
    public VolunteerCardVO.Mine myCard(Long volunteerId) {
        Volunteer v = requireHolder(volunteerId);
        VolunteerCard card = getOrCreate(volunteerId);
        return mine(v, card);
    }

    /** 重置扫码令牌：旧码当场失效，返回新证件。 */
    public VolunteerCardVO.Mine reset(Long volunteerId) {
        Volunteer v = requireHolder(volunteerId);
        getOrCreate(volunteerId);
        cardMapper.resetToken(volunteerId, newToken(), nowSeconds());
        return mine(v, findByVolunteer(volunteerId));
    }

    /** 扫码核验（公开、免登录）。 */
    public VolunteerCardVO.Public verify(String token) {
        if (token == null || !TOKEN_SHAPE.matcher(token.trim()).matches()) {
            throw new BusinessException(INVALID);
        }
        VolunteerCard card = cardMapper.selectOne(Wrappers.<VolunteerCard>lambdaQuery()
                .eq(VolunteerCard::getToken, token.trim()));
        if (card == null || !holderValid(card.getVolunteerId())) {
            throw new BusinessException(INVALID);
        }
        Volunteer v = volunteerMapper.selectById(card.getVolunteerId());
        VolunteerCardVO.Public vo = new VolunteerCardVO.Public();
        vo.setNo(String.valueOf(v.getId()));
        vo.setMaskedName(MaskUtil.maskName(v.getRealName()));
        vo.setAvatarUrl(v.getAvatarUrl());
        vo.setRegisterTime(v.getRegisterTime());
        vo.setServiceHours(hours(rankingQueryService.totalServiceMinutes(v.getId())));
        vo.setActivityCount(rankingQueryService.totalAttendanceCount(v.getId()));
        vo.setVerifyTime(LocalDateTime.now().withNano(0));
        return vo;
    }

    // ================= 内部 =================

    /** 持证人：已实名且账号正常（游客没有证；停用 / 注销的证失效）。 */
    private Volunteer requireHolder(Long volunteerId) {
        if (volunteerId == null || !volunteerQueryService.isActive(volunteerId)) {
            throw new BusinessException("账号状态异常，无法使用志愿者证");
        }
        Volunteer v = volunteerMapper.selectById(volunteerId);
        if (v == null || v.getRegisterTime() == null) {
            throw new BusinessException("请先完成实名注册再领取志愿者证");
        }
        return v;
    }

    private boolean holderValid(Long volunteerId) {
        if (!volunteerQueryService.isActive(volunteerId)) {
            return false;
        }
        Volunteer v = volunteerMapper.selectById(volunteerId);
        return v != null && v.getRegisterTime() != null;
    }

    /** 一人一张：没有就发，并发撞 {@code uk_volunteer} 读回赢家那一张。 */
    private VolunteerCard getOrCreate(Long volunteerId) {
        VolunteerCard card = findByVolunteer(volunteerId);
        if (card != null) {
            return card;
        }
        LocalDateTime now = nowSeconds();
        VolunteerCard c = new VolunteerCard();
        c.setVolunteerId(volunteerId);
        c.setToken(newToken());
        c.setIssueTime(now);
        c.setResetCount(0);
        c.setCreateTime(now);
        c.setUpdateTime(now);
        try {
            cardMapper.insert(c);
            return c;
        } catch (DuplicateKeyException e) {
            VolunteerCard winner = findByVolunteer(volunteerId);
            if (winner == null) {
                throw e;
            }
            return winner;
        }
    }

    private VolunteerCard findByVolunteer(Long volunteerId) {
        return cardMapper.selectOne(Wrappers.<VolunteerCard>lambdaQuery().eq(VolunteerCard::getVolunteerId, volunteerId));
    }

    static String newToken() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private VolunteerCardVO.Mine mine(Volunteer v, VolunteerCard card) {
        VolunteerCardVO.Mine vo = new VolunteerCardVO.Mine();
        vo.setNo(String.valueOf(v.getId()));
        vo.setRealName(v.getRealName());
        vo.setAvatarUrl(v.getAvatarUrl());
        vo.setGenderName(v.getGender() == null ? null : v.getGender().getLabel());
        vo.setRegisterTime(v.getRegisterTime());
        vo.setDuty(tempLeaderQueryService.isTempLeader(v.getId()) ? "活动临时负责人" : "志愿者");
        vo.setSquadName(v.getSquadId() == null ? null : squadQueryService.listNamesByIds(List.of(v.getSquadId())).get(v.getSquadId()));
        vo.setServiceHours(hours(rankingQueryService.totalServiceMinutes(v.getId())));
        vo.setActivityCount(rankingQueryService.totalAttendanceCount(v.getId()));
        vo.setIssueTime(card.getIssueTime());
        fillCode(vo, card.getToken());
        return vo;
    }

    private void fillCode(VolunteerCardVO.Mine vo, String token) {
        if (miniappCodeService.configured()) {
            String cached = cacheGet(token);
            if (cached == null) {
                byte[] png = miniappCodeService.unlimited(token, properties.getMiniappPage(), properties.getMiniappEnvVersion());
                if (png != null && png.length > 0) {
                    cached = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
                    cachePut(token, cached);
                }
            }
            if (cached != null) {
                vo.setQrType("MINIAPP_CODE");
                vo.setQrImage(cached);
                vo.setQrContent(token);
                return;
            }
        }
        String url = properties.getVerifyUrl();
        String content = StringUtils.hasText(url) ? url + (url.contains("?") ? "&" : "?") + "token=" + token : QR_PREFIX + token;
        vo.setQrType("QR_CODE");
        vo.setQrContent(content);
        vo.setQrImage(QrCodeUtil.toPngDataUrl(content, 430));
    }

    private String cacheGet(String token) {
        try {
            RBucket<String> b = redissonClient.getBucket(CODE_CACHE_PREFIX + token, StringCodec.INSTANCE);
            return b.get();
        } catch (Exception e) {
            log.warn("[VolunteerCard] 读小程序码缓存失败：{}", e.getMessage());
            return null;
        }
    }

    private void cachePut(String token, String dataUrl) {
        try {
            redissonClient.getBucket(CODE_CACHE_PREFIX + token, StringCodec.INSTANCE)
                    .set(dataUrl, Duration.ofSeconds(Math.max(60, properties.getCodeCacheSeconds())));
        } catch (Exception e) {
            log.warn("[VolunteerCard] 写小程序码缓存失败：{}", e.getMessage());
        }
    }

    /**
     * 取整到秒：MySQL {@code DATETIME} 写入是<b>四舍五入</b>，带纳秒的 {@code .509} 会存成下一秒——
     * 第一次发证时出参用的是内存里的值、之后读库，两次看到的签发时间就对不上（用例当场撞出）。
     */
    private static LocalDateTime nowSeconds() {
        return LocalDateTime.now().withNano(0);
    }

    static BigDecimal hours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
    }
}
