package com.hengde.auth.service;

import com.hengde.auth.config.AuthProperties;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 后台登录防爆破：失败计数 + 临时锁定（账号维度与来源 IP 维度，Redis 计数）。
 *
 * <p>口径（阈值见 {@link AuthProperties}）：</p>
 * <ul>
 *     <li><b>账号维度</b>——同一用户名在窗口内连续失败 {@code loginMaxFailures} 次，
 *         锁定 {@code loginLockSeconds}（达到上限时把 key 过期重置为满时长，保证锁满窗口）；
 *         登录成功即清零，正常用户偶尔输错不受影响；</li>
 *     <li><b>IP 维度</b>——同一来源 IP 在窗口内累计失败 {@code loginIpMaxFailures} 次同样拦截，
 *         防换着用户名喷洒式撞库；登录成功<b>不清</b> IP 计数（共享出口 NAT 下他人失败不应被
 *         一次成功冲掉），等窗口自然过期。</li>
 * </ul>
 *
 * <p>计数 key 只用 {@code INCR} 原子自增；BCrypt 已拖慢单次尝试，这里补「次数上限」闭合在线爆破。
 * 账号维度锁定意味着知道用户名的人可恶意把号锁住（拒绝服务换爆破防护）——窗口短（15 分钟）、
 * 且后台为内部少量账号，可接受；如被骚扰可临时调大阈值。</p>
 *
 * <p>依赖按项目约定 setter 注入。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class LoginProtectionService {

    private static final String USER_FAIL_PREFIX = "auth:admin:login:fail:user:";
    private static final String IP_FAIL_PREFIX = "auth:admin:login:fail:ip:";

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

    /**
     * 登录前置检查：账号或来源 IP 已达失败上限则直接拒绝（不再触发密码校验）。
     *
     * @param username 登录账号
     * @param clientIp 来源 IP（可空，空则跳过 IP 维度）
     * @throws BusinessException 已锁定时抛出
     */
    public void checkNotLocked(String username, String clientIp) {
        if (currentCount(USER_FAIL_PREFIX + username) >= authProperties.getLoginMaxFailures()) {
            throw new BusinessException("登录失败次数过多，账号已临时锁定，请稍后再试");
        }
        if (StringUtils.hasText(clientIp)
                && currentCount(IP_FAIL_PREFIX + clientIp) >= authProperties.getLoginIpMaxFailures()) {
            throw new BusinessException("登录失败次数过多，请稍后再试");
        }
    }

    /**
     * 记录一次登录失败（账号不存在或密码错误）。账号计数达到上限时把过期时间重置为满锁定时长。
     *
     * @param username 登录账号
     * @param clientIp 来源 IP（可空）
     */
    public void onLoginFailed(String username, String clientIp) {
        long userFails = bump(USER_FAIL_PREFIX + username);
        if (userFails >= authProperties.getLoginMaxFailures()) {
            // 锁定从「达到上限」时刻起算满时长，而非从第一次失败起算
            redisUtil.expire(USER_FAIL_PREFIX + username, authProperties.getLoginLockSeconds());
            log.warn("[Auth] 后台账号 [{}] 连续登录失败 {} 次，已临时锁定 {} 秒（来源 IP：{}）",
                    username, userFails, authProperties.getLoginLockSeconds(), clientIp);
        }
        if (StringUtils.hasText(clientIp)) {
            bump(IP_FAIL_PREFIX + clientIp);
        }
    }

    /**
     * 登录成功：清零该账号的失败计数（IP 计数留给窗口自然过期，见类注释）。
     *
     * @param username 登录账号
     */
    public void onLoginSucceeded(String username) {
        redisUtil.delete(USER_FAIL_PREFIX + username);
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
