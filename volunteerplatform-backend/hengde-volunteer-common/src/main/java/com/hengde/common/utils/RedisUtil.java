package com.hengde.common.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 操作工具类。
 *
 * <p>把 {@link RedisTemplate} 的常用操作包一层，业务里注入 RedisUtil 直接调用即可，
 * 不必每次都写 {@code redisTemplate.opsForValue().xxx}。典型用途：缓存数据、存短信验证码
 * （带过期时间）、计数等。</p>
 *
 * <p>注意：本类是 Spring 容器管理的 bean（{@code @Component}），依赖按项目约定用
 * <b>setter 注入</b>，{@code @Autowired} 标在手写的 setter 上（不是字段、不是构造器）。</p>
 *
 * @author hengde
 */
@Component
public class RedisUtil {

    /** Redis 操作模板，由 {@link com.hengde.common.config.RedisConfig} 提供的 bean 注入 */
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * setter 注入：Spring 启动时会调用这个方法把 RedisTemplate 传进来。
     * Lombok 的 @Setter 生成的 setter 不带 @Autowired，所以这里手写。
     *
     * @param redisTemplate Redis 操作模板
     */
    @Autowired
    public void setRedisTemplate(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ==================== 通用 key 操作 ====================

    /**
     * 设置过期时间（秒）。
     *
     * @param key     键
     * @param seconds 过期秒数
     * @return 设置成功返回 true
     */
    public boolean expire(String key, long seconds) {
        return Boolean.TRUE.equals(redisTemplate.expire(key, seconds, TimeUnit.SECONDS));
    }

    /**
     * 获取剩余过期时间（秒）。
     *
     * @param key 键
     * @return 剩余秒数；-1 表示永不过期，-2 表示 key 不存在
     */
    public long getExpire(String key) {
        Long expire = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return expire == null ? -2 : expire;
    }

    /**
     * 判断 key 是否存在。
     *
     * @param key 键
     * @return 存在返回 true
     */
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 删除一个 key。
     *
     * @param key 键
     * @return 删除成功返回 true
     */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    /**
     * 批量删除 key。
     *
     * @param keys 键集合
     * @return 实际删除的数量
     */
    public long delete(Collection<String> keys) {
        Long count = redisTemplate.delete(keys);
        return count == null ? 0 : count;
    }

    // ==================== String（普通 key-value） ====================

    /**
     * 存值（不过期）。
     *
     * @param key   键
     * @param value 值（对象会被自动转成 JSON）
     */
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 存值并设置过期时间。常用于短信验证码等。
     *
     * @param key     键
     * @param value   值
     * @param seconds 过期秒数
     */
    public void set(String key, Object value, long seconds) {
        redisTemplate.opsForValue().set(key, value, seconds, TimeUnit.SECONDS);
    }

    /**
     * 取值。
     *
     * @param key 键
     * @return 值；key 不存在返回 null
     */
    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 自增（原子操作，常用于计数、生成序号）。
     *
     * <p><b>注意：</b>计数类 key 请「只用 increment/decrement，不要先 {@link #set}」。
     * increment 走 Redis 原生 INCR 命令、不经过 value 的 JSON 序列化；而 set 会把数字存成
     * JSON 字符串，之后再 increment 会报「value is not an integer」。</p>
     *
     * @param key   键
     * @param delta 每次增加的步长（正数）
     * @return 增加后的值
     */
    public long increment(String key, long delta) {
        Long result = redisTemplate.opsForValue().increment(key, delta);
        return result == null ? 0 : result;
    }

    /**
     * 自减。
     *
     * @param key   键
     * @param delta 每次减少的步长（正数）
     * @return 减少后的值
     */
    public long decrement(String key, long delta) {
        Long result = redisTemplate.opsForValue().decrement(key, delta);
        return result == null ? 0 : result;
    }

    // ==================== Hash（哈希，适合存对象的多个字段） ====================

    /**
     * 往 hash 里放一个字段。
     *
     * @param key   键
     * @param field 字段名
     * @param value 字段值
     */
    public void hSet(String key, String field, Object value) {
        redisTemplate.opsForHash().put(key, field, value);
    }

    /**
     * 取 hash 里某个字段的值。
     *
     * @param key   键
     * @param field 字段名
     * @return 字段值；不存在返回 null
     */
    public Object hGet(String key, String field) {
        return redisTemplate.opsForHash().get(key, field);
    }

    /**
     * 删除 hash 里的一个或多个字段。
     *
     * @param key    键
     * @param fields 要删除的字段名
     * @return 实际删除的字段数量
     */
    public long hDelete(String key, Object... fields) {
        return redisTemplate.opsForHash().delete(key, fields);
    }

    // ==================== List（列表，可当队列/栈用） ====================

    /**
     * 从列表左侧（头部）压入一个元素。
     *
     * @param key   键
     * @param value 值
     * @return 压入后列表长度
     */
    public long lPush(String key, Object value) {
        Long size = redisTemplate.opsForList().leftPush(key, value);
        return size == null ? 0 : size;
    }

    /**
     * 从列表右侧（尾部）压入一个元素。
     *
     * @param key   键
     * @param value 值
     * @return 压入后列表长度
     */
    public long rPush(String key, Object value) {
        Long size = redisTemplate.opsForList().rightPush(key, value);
        return size == null ? 0 : size;
    }

    /**
     * 按下标范围取列表元素。
     *
     * @param key   键
     * @param start 起始下标（0 开始）
     * @param end   结束下标（-1 表示到末尾）
     * @return 该范围内的元素列表
     */
    public List<Object> lRange(String key, long start, long end) {
        return redisTemplate.opsForList().range(key, start, end);
    }

    // ==================== Set（集合，元素不重复） ====================

    /**
     * 往集合里添加元素。
     *
     * @param key    键
     * @param values 一个或多个值
     * @return 实际新增的元素数量（已存在的不计）
     */
    public long sAdd(String key, Object... values) {
        Long count = redisTemplate.opsForSet().add(key, values);
        return count == null ? 0 : count;
    }

    /**
     * 取集合所有元素。
     *
     * @param key 键
     * @return 元素集合
     */
    public Set<Object> sMembers(String key) {
        return redisTemplate.opsForSet().members(key);
    }

    /**
     * 判断某个元素是否在集合中。
     *
     * @param key   键
     * @param value 待判断的值
     * @return 在集合中返回 true
     */
    public boolean sIsMember(String key, Object value) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, value));
    }
}
