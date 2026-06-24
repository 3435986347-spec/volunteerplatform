package com.hengde.auth.service;

import com.hengde.auth.config.AuthProperties;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 登录防爆破：失败计数 + 临时锁定（账号维度与来源 IP 维度，Redis 计数）。两端共用同一套机制、
 * <b>各用独立 key 前缀互不污染</b>——管理端（{@code auth:admin:login:fail:*}，标识=用户名）、
 * 志愿者端（{@code auth:volunteer:login:fail:*}，标识=<b>phoneHash 而非明文手机号</b>，避免把手机号写进 Redis）。
 *
 * <p>口径（阈值见 {@link AuthProperties}，两端共用）：</p>
 * <ul>
 *     <li><b>账号维度</b>——同一账号在窗口内连续失败 {@code loginMaxFailures} 次，
 *         锁定 {@code loginLockSeconds}（达到上限时把 key 过期重置为满时长）；登录成功即清零；</li>
 *     <li><b>IP 维度</b>——同一来源 IP 在窗口内累计失败 {@code loginIpMaxFailures} 次同样拦截，
 *         防换账号喷洒式撞库；登录成功<b>不清</b> IP 计数（共享 NAT 下他人失败不被一次成功冲掉），
 *         等窗口自然过期。</li>
 * </ul>
 *
 * <p>计数只用 {@code INCR} 原子自增；BCrypt 已拖慢单次尝试，这里补「次数上限」闭合在线爆破。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class LoginProtectionService {

    /** 管理端 key 前缀（标识=用户名，保持历史不变） */
    private static final String ADMIN_ACCOUNT_PREFIX = "auth:admin:login:fail:user:";
    private static final String ADMIN_IP_PREFIX = "auth:admin:login:fail:ip:";
    /** 志愿者端 key 前缀（标识=phoneHash） */
    private static final String VOL_ACCOUNT_PREFIX = "auth:volunteer:login:fail:id:";
    private static final String VOL_IP_PREFIX = "auth:volunteer:login:fail:ip:";

    private RedisUtil redisUtil;
    private AuthProperties authProperties;

    @Autowired
    public void setRedisUtil(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
    }

    @Autowired
    public void setAuthProperties(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    // ==================== 管理端（标识=用户名） ====================

    public void checkNotLocked(String username, String clientIp) {
        checkNotLocked(ADMIN_ACCOUNT_PREFIX, ADMIN_IP_PREFIX, username, clientIp);
    }

    public void onLoginFailed(String username, String clientIp) {
        onLoginFailed(ADMIN_ACCOUNT_PREFIX, ADMIN_IP_PREFIX, username, clientIp, "后台账号");
    }

    public void onLoginSucceeded(String username) {
        redisUtil.delete(ADMIN_ACCOUNT_PREFIX + username);
    }

    // ==================== 志愿者端（标识=phoneHash，不落明文手机号） ====================

    public void checkVolunteerNotLocked(String phoneHash, String clientIp) {
        checkNotLocked(VOL_ACCOUNT_PREFIX, VOL_IP_PREFIX, phoneHash, clientIp);
    }

    public void onVolunteerLoginFailed(String phoneHash, String clientIp) {
        onLoginFailed(VOL_ACCOUNT_PREFIX, VOL_IP_PREFIX, phoneHash, clientIp, "志愿者手机号");
    }

    public void onVolunteerLoginSucceeded(String phoneHash) {
        redisUtil.delete(VOL_ACCOUNT_PREFIX + phoneHash);
    }

    // ==================== 共用核心 ====================

    /**
     * 登录前置检查：账号或来源 IP 已达失败上限则直接拒绝（不再触发密码校验）。
     *
     * @throws BusinessException 已锁定时抛出
     */
    private void checkNotLocked(String acctPrefix, String ipPrefix, String accountId, String clientIp) {
        if (StringUtils.hasText(accountId)
                && currentCount(acctPrefix + accountId) >= authProperties.getLoginMaxFailures()) {
            throw new BusinessException("登录失败次数过多，账号已临时锁定，请稍后再试");
        }
        if (StringUtils.hasText(clientIp)
                && currentCount(ipPrefix + clientIp) >= authProperties.getLoginIpMaxFailures()) {
            throw new BusinessException("登录失败次数过多，请稍后再试");
        }
    }

    /** 记录一次登录失败。账号计数达到上限时把过期时间重置为满锁定时长。 */
    private void onLoginFailed(String acctPrefix, String ipPrefix, String accountId, String clientIp, String label) {
        if (StringUtils.hasText(accountId)) {
            long fails = bump(acctPrefix + accountId);
            if (fails >= authProperties.getLoginMaxFailures()) {
                redisUtil.expire(acctPrefix + accountId, authProperties.getLoginLockSeconds());
                log.warn("[Auth] {} 连续登录失败 {} 次，已临时锁定 {} 秒（来源 IP：{}）",
                        label, fails, authProperties.getLoginLockSeconds(), clientIp);
            }
        }
        if (StringUtils.hasText(clientIp)) {
            bump(ipPrefix + clientIp);
        }
    }

    /** INCR 原子自增，首次自增时设窗口过期。 */
    private long bump(String key) {
        long count = redisUtil.increment(key, 1);
        if (count == 1) {
            redisUtil.expire(key, authProperties.getLoginLockSeconds());
        }
        return count;
    }

    /** 读当前计数；key 不存在记 0。INCR 写入的是裸数字，JSON 反序列化为 Number。 */
    private long currentCount(String key) {
        Object v = redisUtil.get(key);
        return v instanceof Number n ? n.longValue() : 0;
    }
}
