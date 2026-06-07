package com.hengde.common.lock;

import com.hengde.common.exception.BusinessException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁工具（Redisson）：把「死锁安全的获取顺序 + watchdog 续期 + 提交后释放」纪律集中一处，
 * 供各领域以不同 key 前缀复用（如 {@code lock:enroll:volunteer:} / {@code lock:group:volunteer:}）。
 *
 * <p>无 bean、无 DI（与 {@code PasswordUtil}/{@code MaskUtil} 等静态工具同风格）：调用方传入自己注入的
 * {@link RedissonClient}。common 仅依赖 Redisson 核心库（API），不引 starter，故不会在 common 自身上下文
 * 创建 RedissonClient——只有用锁的领域模块（activity/organization）经各自的 starter 提供实例。</p>
 *
 * <p><b>锁与事务约定</b>：锁须在事务<b>之外</b>获取、临界区内用 {@code TransactionTemplate} 显式提交，
 * 提交完成后才回到 {@code finally} 释放锁——避免「先放锁、后提交」窗口里另一请求读到未提交数据。
 * 因此 {@code action} 内部通常是 {@code transactionTemplate.execute(...)}。</p>
 *
 * @author hengde
 */
public final class DistributedLockSupport {

    private DistributedLockSupport() {
    }

    /** tryLock 等待秒数（与各领域原实现一致）。 */
    private static final long WAIT_SEC = 5;

    /**
     * 在单把锁（{@code key}）内执行动作；锁未拿到抛业务异常，结束时只释放本线程持有的锁。
     */
    public static <T> T runLocked(RedissonClient client, String key, Supplier<T> action) {
        RLock lock = client.getLock(key);
        if (!tryLock(lock)) {
            throw new BusinessException("操作太频繁，请稍后再试");
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 同时持有多把锁（{@code keyPrefix + id}）后执行动作：ids <b>升序去重</b>后按序加锁——全局一致的资源排序
     * 杜绝多锁互等死锁；任一锁未拿到抛业务异常；结束时<b>反序</b>释放、且只释放本线程持有的锁。
     */
    public static <T> T runLockedMany(RedissonClient client, String keyPrefix, Collection<Long> ids, Supplier<T> action) {
        // TreeSet：升序 + 去重 → 全局一致加锁顺序
        List<Long> ordered = new ArrayList<>(new TreeSet<>(ids));
        List<RLock> acquired = new ArrayList<>(ordered.size());
        try {
            for (Long id : ordered) {
                RLock lock = client.getLock(keyPrefix + id);
                if (!tryLock(lock)) {
                    throw new BusinessException("操作太频繁，请稍后再试");
                }
                acquired.add(lock);
            }
            return action.get();
        } finally {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                RLock l = acquired.get(i);
                if (l.isHeldByCurrentThread()) {
                    l.unlock();
                }
            }
        }
    }

    /**
     * 不指定 leaseTime：走 Redisson watchdog 自动续期，避免固定租期在慢 SQL/GC/DB 抖动下
     * 「事务未提交、锁已到期释放」从而被另一请求抢锁读到未提交数据。
     */
    private static boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(WAIT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("操作被中断，请重试");
        }
    }
}
