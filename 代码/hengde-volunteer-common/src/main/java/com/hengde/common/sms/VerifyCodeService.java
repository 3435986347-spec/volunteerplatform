package com.hengde.common.sms;

import cn.hutool.core.util.RandomUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import com.hengde.common.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 短信验证码服务：生成、存储、发送、校验一条龙。
 *
 * <p>放在 common 是因为它编织的三样东西都在 common：随机码生成、{@link RedisUtil} 存储、
 * {@link SmsService} 发送。auth（注册/找回密码）、user（换绑手机号）等领域共用本服务，
 * 业务侧只管「发」和「验」，不必各自重复实现一套验证码逻辑。</p>
 *
 * <p>验证码以 {@code sms:code:{场景}:{手机号}} 为 key 存 Redis，带有效期；
 * 校验通过即删除（一次性消费）。同场景同号在重发间隔内重复请求会被拦截。</p>
 *
 * <p><b>滥用防护</b>（{@link SmsProperties} 可调）：</p>
 * <ul>
 *     <li>发送侧——同手机号跨场景 24h 限量、同来源 IP 每小时/每日限量（IP 由调用方经
 *         {@code IpUtil} 取自请求传入，未传则跳过 IP 维度），防短信轰炸与话费消耗；</li>
 *     <li>校验侧——同一条码累计错 {@code verifyMaxAttempts} 次即作废，防 6 位码在
 *         有效期内被暴力穷举。</li>
 * </ul>
 * <p>计数 key 只用 {@code INCR} 原子自增（见 {@link RedisUtil#increment}），先计数后放行，
 * 并发刷量不致绕过上限。</p>
 *
 * <p>依赖按项目约定用 setter 注入，{@code @Autowired} 标在手写 setter 上。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class VerifyCodeService {

    private static final String KEY_PREFIX = "sms:code:";
    /** 校验失败计数 key 前缀（与验证码同生命周期） */
    private static final String FAIL_PREFIX = "sms:code:fail:";
    /** 发送量计数 key 前缀：手机号日维度 / IP 小时维度 / IP 日维度 */
    private static final String SEND_PHONE_DAY_PREFIX = "sms:send:phone:day:";
    private static final String SEND_IP_HOUR_PREFIX = "sms:send:ip:hour:";
    private static final String SEND_IP_DAY_PREFIX = "sms:send:ip:day:";

    private static final long HOUR_SECONDS = 3600;
    private static final long DAY_SECONDS = 86400;

    private RedisUtil redisUtil;
    private SmsService smsService;
    private SmsProperties properties;

    @Autowired
    public void setRedisUtil(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
    }

    @Autowired
    public void setSmsService(SmsService smsService) {
        this.smsService = smsService;
    }

    @Autowired
    public void setProperties(SmsProperties properties) {
        this.properties = properties;
    }

    /**
     * 生成验证码并发送（无来源 IP 维度限流；有 IP 的入口请用三参重载）。
     *
     * @param phone 手机号
     * @param scene 场景，见 {@link SmsScene}
     * @throws BusinessException 重发过于频繁或触发发送限额时抛出
     */
    public void sendCode(String phone, String scene) {
        sendCode(phone, scene, null);
    }

    /**
     * 生成验证码并发送，带滥用防护：同号重发间隔、手机号日限量、来源 IP 时/日限量。
     *
     * @param phone    手机号
     * @param scene    场景，见 {@link SmsScene}
     * @param clientIp 来源 IP（控制器经 {@code IpUtil.getClientIp} 取得；为空则跳过 IP 维度限流）
     * @throws BusinessException 重发过于频繁或触发发送限额时抛出
     */
    public void sendCode(String phone, String scene, String clientIp) {
        String key = key(scene, phone);

        long ttl = redisUtil.getExpire(key);
        if (ttl > properties.getCodeExpireSeconds() - properties.getCodeResendSeconds()) {
            // 上一条验证码是在重发间隔内发出的，拦截
            throw new BusinessException("验证码发送过于频繁，请稍后再试");
        }

        // 限量计数先于发送（INCR 原子），并发刷量不致绕过；发送失败也占当期额度，属可接受成本
        checkSendQuota(SEND_PHONE_DAY_PREFIX + phone, properties.getSendDailyCapPerPhone(), DAY_SECONDS,
                "该手机号今日验证码发送次数已达上限，请明天再试");
        if (StringUtils.hasText(clientIp)) {
            checkSendQuota(SEND_IP_HOUR_PREFIX + clientIp, properties.getSendHourlyCapPerIp(), HOUR_SECONDS,
                    "操作过于频繁，请稍后再试");
            checkSendQuota(SEND_IP_DAY_PREFIX + clientIp, properties.getSendDailyCapPerIp(), DAY_SECONDS,
                    "操作过于频繁，请明天再试");
        }

        String code = RandomUtil.randomNumbers(properties.getCodeLength());
        redisUtil.set(key, code, properties.getCodeExpireSeconds());
        // 新码重置失败计数（计数与码同生命周期）
        redisUtil.delete(failKey(scene, phone));
        smsService.sendVerifyCode(phone, code);
    }

    /**
     * 校验验证码，通过则消费（删除），失败抛 {@link ResultCode#SMS_CODE_ERROR}。
     * 同一条码累计错 {@code verifyMaxAttempts} 次即作废（连同计数一起删除），须重新获取。
     *
     * @param phone 手机号
     * @param scene 场景，见 {@link SmsScene}
     * @param code  用户提交的验证码
     */
    public void verify(String phone, String scene, String code) {
        String key = key(scene, phone);
        String failKey = failKey(scene, phone);
        Object stored = redisUtil.get(key);
        if (stored == null || !stored.toString().equals(code)) {
            // 仅在「码还存在但填错」时计数；码不存在/已过期没有可穷举的目标，不必计
            if (stored != null) {
                long fails = redisUtil.increment(failKey, 1);
                if (fails == 1) {
                    redisUtil.expire(failKey, properties.getCodeExpireSeconds());
                }
                if (fails >= properties.getVerifyMaxAttempts()) {
                    redisUtil.delete(key);
                    redisUtil.delete(failKey);
                    throw new BusinessException("验证码错误次数过多已失效，请重新获取");
                }
            }
            throw new BusinessException(ResultCode.SMS_CODE_ERROR);
        }
        redisUtil.delete(key);
        redisUtil.delete(failKey);
    }

    /** 限量计数：INCR 原子自增，首次自增时设窗口过期；超过上限抛业务异常。 */
    private void checkSendQuota(String key, int cap, long windowSeconds, String message) {
        long count = redisUtil.increment(key, 1);
        if (count == 1) {
            redisUtil.expire(key, windowSeconds);
        }
        if (count > cap) {
            throw new BusinessException(message);
        }
    }

    private String key(String scene, String phone) {
        return KEY_PREFIX + scene + ":" + phone;
    }

    private String failKey(String scene, String phone) {
        return FAIL_PREFIX + scene + ":" + phone;
    }
}
