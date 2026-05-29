package com.hengde.common.sms;

import cn.hutool.core.util.RandomUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import com.hengde.common.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
 * <p>依赖按项目约定用 setter 注入，{@code @Autowired} 标在手写 setter 上。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class VerifyCodeService {

    private static final String KEY_PREFIX = "sms:code:";

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
     * 生成验证码并发送。
     *
     * @param phone 手机号
     * @param scene 场景，见 {@link SmsScene}
     * @throws BusinessException 重发过于频繁时抛出
     */
    public void sendCode(String phone, String scene) {
        String key = key(scene, phone);

        long ttl = redisUtil.getExpire(key);
        if (ttl > properties.getCodeExpireSeconds() - properties.getCodeResendSeconds()) {
            // 上一条验证码是在重发间隔内发出的，拦截
            throw new BusinessException("验证码发送过于频繁，请稍后再试");
        }

        String code = RandomUtil.randomNumbers(properties.getCodeLength());
        redisUtil.set(key, code, properties.getCodeExpireSeconds());
        smsService.sendVerifyCode(phone, code);
    }

    /**
     * 校验验证码，通过则消费（删除），失败抛 {@link ResultCode#SMS_CODE_ERROR}。
     *
     * @param phone 手机号
     * @param scene 场景，见 {@link SmsScene}
     * @param code  用户提交的验证码
     */
    public void verify(String phone, String scene, String code) {
        String key = key(scene, phone);
        Object stored = redisUtil.get(key);
        if (stored == null || !stored.toString().equals(code)) {
            throw new BusinessException(ResultCode.SMS_CODE_ERROR);
        }
        redisUtil.delete(key);
    }

    private String key(String scene, String phone) {
        return KEY_PREFIX + scene + ":" + phone;
    }
}
