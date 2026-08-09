package com.hengde.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.constant.SanctionScope;
import com.hengde.auth.dao.VolunteerMapper;
import com.hengde.auth.dao.VolunteerSanctionMapper;
import com.hengde.auth.entity.VolunteerSanction;
import com.hengde.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 处置措施的<b>只读窄接口</b>——业务入口的闸门走这里。
 *
 * <p><b>为什么这张表落在 auth 而不是 honor</b>：处置是账号能力状态，与 {@code volunteer.status} 同一性质；
 * 而闸门必须挂在报名、签到、社区发帖这些<b>业务入口</b>上，那些入口在 activity / publicity。
 * 若把它放进 honor，activity 与 publicity 就要反向依赖 honor，与既定方向 {@code honor → activity} 成环。
 * auth 是三者都已经依赖的模块，放这里没有新的依赖负担。</p>
 *
 * <p><b>「是否生效」永远按时间现算，不读某个「已过期」状态位</b>：
 * 若靠定时任务把到期的处置改成失效，任务漏跑一次处罚就会超期继续生效，
 * 而「到期即自动恢复」是对志愿者的承诺，不该取决于某个 cron 有没有跑成功。</p>
 *
 * @author hengde
 */
@Service
public class SanctionQueryService {

    private static final DateTimeFormatter DEADLINE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private VolunteerSanctionMapper sanctionMapper;
    private VolunteerMapper volunteerMapper;

    @Autowired
    public void setSanctionMapper(VolunteerSanctionMapper sanctionMapper) {
        this.sanctionMapper = sanctionMapper;
    }

    @Autowired
    public void setVolunteerMapper(VolunteerMapper volunteerMapper) {
        this.volunteerMapper = volunteerMapper;
    }

    /**
     * 该志愿者在这个能力域上是否正被限制。
     *
     * <p>{@link SanctionScope#ALL}（拒绝使用本程序）<b>蕴含所有能力域</b>——
     * 否则「拒绝使用」反而比「限制参加活动」管得少。</p>
     *
     * <p><b>不加锁，只作展示/断言用</b>。真正要拦住一个动作请用
     * {@link #assertNotRestricted}——差别不在报错文案，而在那条读是否与处置写入串行化。</p>
     */
    public boolean isRestricted(Long volunteerId, int scope) {
        return findBlocking(volunteerId, scope) != null;
    }

    /**
     * 闸门：正被限制就抛业务异常，报错里带上解除时间。<b>必须在事务内调用。</b>
     *
     * <p>报错必须说清<b>到什么时候</b>：只说「你被限制了」会让人以为是永久的，
     * 而 P109 画的处罚恰恰是有期限的（「限制参加活动7天」）。</p>
     *
     * <p><b>这里需要两把锁，各管一半，缺一不可</b>：闸门与处置写入分处两个事务，
     * 报名自己的 {@code lock:enroll:volunteer:} 只串行化报名之间，管不住奖惩审核那一侧，
     * 于是「闸门查无处罚 → 审核提交处罚 → 报名提交」这个窗口无人看守。</p>
     * <ul>
     *   <li><b>串行化到写入方开工那一刻</b>——志愿者父行的 S 锁（{@code impose} 那边取 X）。
     *       处置行被插入之后，下面那条当前读的间隙锁本来也挡得住；父行锁买的是<b>更早的一段</b>：
     *       审核方要先 CAS 改单、再入积分流水，最后才插处置行，这期间闸门与它没有任何锁冲突，
     *       会读到「没有处罚」并放行。而 {@code impose} 第一件事就是取父行 X，闸门取父行 S 即可对齐。
     *       论证见 {@link com.hengde.auth.dao.VolunteerMapper#selectByIdForUpdate}。</li>
     *   <li><b>可见性</b>——处置查询本身必须是<b>当前读</b>
     *       （{@link VolunteerSanctionMapper#selectBlockingForShare}）。父行锁只保证「对方提交不完
     *       我就等着」，不保证「等到了能看见」：RR 的读视图在本事务第一次一致性读时就定死，
     *       而 {@code doProxyEnroll} 出于隐私顺序必须先跑同组校验（普通读），读视图那时已经建立。
     *       <b>只加父行锁而仍用快照读，闸门会静默漏掉刚提交的处罚。</b></li>
     * </ul>
     *
     * <p>两把都取 S，不引发升级：调用方（报名/补录/代报名/签到）之后写的是报名与考勤，
     * 既不改志愿者这一行，也不改处置表。</p>
     *
     * <p>志愿者行不存在时<b>不在此报错</b>：闸门只回答「有没有被限制」，
     * 「这个人还在不在」是各入口自己的校验，在这里抢答只会把报错文案指向错误的方向。</p>
     *
     * @param action 动作名，拼进报错文案，如「报名」「签到」
     */
    public void assertNotRestricted(Long volunteerId, int scope, String action) {
        requireTransaction();
        if (volunteerId == null) {
            return;
        }
        // 锁住串行化父行；返回值用不上，要的就是这把 S 锁
        volunteerMapper.selectByIdForShare(volunteerId);
        throwIfBlocked(volunteerId, scope, action);
    }

    /**
     * 事务检查。
     *
     * <p>不在事务里，{@code FOR SHARE} 会在 autocommit 下随语句结束立刻释放：
     * 闸门看着还在、串行化边界已经没了，而且<b>没有任何征兆</b>——查询照常返回、用例照常绿。
     * 这类静默失效正是本闸门要防的东西，宁可让调用方在开发期就炸掉。</p>
     */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "处置闸门必须在事务内调用，否则行锁不生效（只读展示请用 isRestricted）");
        }
    }

    /**
     * 读一次处置并在被挡时抛错。<b>调用方必须已持有该志愿者的父行锁。</b>
     *
     * <p><b>这里必须是当前读，不能复用 {@link #findBlocking}</b>：父行锁只买到
     * 「对方提交不完我就等着」，买不到「等到之后能看见」——RR 的读视图在本事务
     * 第一次一致性读时就定死了。而 {@code doProxyEnroll} 出于隐私顺序，闸门之前
     * 必然先跑过 {@code requireSameActiveGroup}（普通读），读视图那时已经建立，
     * 普通查询看不见这期间提交的处罚。完整论证见 mapper。</p>
     *
     * <p>{@code now} 不截断到秒：口径与 {@link #findBlocking} / {@link #activeSanctions} 一致。
     * （{@code impose} 那边截断的是<b>写入</b>的 {@code effective_time}，为的是躲开 DATETIME
     * 的四舍五入；读这一侧再截一次只会把生效时刻往后推，方向正好反了。）</p>
     */
    private void throwIfBlocked(Long volunteerId, int scope, String action) {
        VolunteerSanction s = sanctionMapper.selectBlockingForShare(
                volunteerId, scope, SanctionScope.ALL, LocalDateTime.now());
        if (s == null) {
            return;
        }
        String until = s.getExpireTime() == null
                ? "（未设解除期限）"
                : "，至 " + s.getExpireTime().format(DEADLINE_FMT) + " 自动解除";
        throw new BusinessException("您当前被" + SanctionScope.labelOf(s.getScope())
                + until + "，暂不能" + action + "。如有异议可在奖惩记录中查看处罚详情并申诉");
    }

    /**
     * 闸门的<b>批量</b>版：一次判定多名志愿者（代报名）。<b>必须在事务内调用。</b>
     *
     * <p><b>为什么不能直接循环调 {@link #assertNotRestricted}</b>——那样会有间隙锁死锁：
     * 单次判定的顺序是「锁父行 → 当前读处置」，而处置那条 {@code FOR SHARE} 在 RR 下
     * 除了锁到的行还会锁住<b>它们之间的间隙</b>，范围由 {@code idx_volunteer_scope} 的相邻条目决定，
     * 很可能一直盖到本批后面某个志愿者身上。于是：</p>
     * <pre>
     *   闸门：持有 v1 的父行锁 + 覆盖 v5 的间隙锁  →  等 v5 的父行锁
     *   impose(v5)：持有 v5 的父行锁              →  等那把覆盖 v5 的间隙锁
     * </pre>
     * <p>成环，MySQL 判 ER_LOCK_DEADLOCK(1213)，代报名报 500。</p>
     *
     * <p><b>修法是把两个阶段分开</b>：先按 id 升序把本批<b>全部父行锁</b>拿齐，再去读处置。
     * 这样任何一个 {@code impose} 都会在它自己的父行上先被挡住，根本走不到插入那一步，
     * 也就不会去争那把间隙锁。升序则保证多个代报名之间彼此不成环（与
     * {@code DistributedLockSupport.runLockedMany} 同一条纪律）。</p>
     *
     * <p>单人入口（报名/补录/签到）不受此影响：只持有一把父行锁，拿完就读、读完就走，
     * 不存在「持有间隙锁再去等另一把父行锁」的第二步，故仍用 {@link #assertNotRestricted}。</p>
     *
     * @param volunteerIds 本批志愿者；null 与重复项会被剔除
     */
    public void assertNoneRestricted(Collection<Long> volunteerIds, int scope, String action) {
        requireTransaction();
        if (volunteerIds == null || volunteerIds.isEmpty()) {
            return;
        }
        List<Long> sorted = volunteerIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        // 阶段一：升序拿齐全部父行锁
        for (Long id : sorted) {
            volunteerMapper.selectByIdForShare(id);
        }
        // 阶段二：此时任何 impose 都已被挡在各自的父行上，可以安心读
        for (Long id : sorted) {
            throwIfBlocked(id, scope, action);
        }
    }

    /** 当前生效中的全部处置，供「我的奖惩」与后台展示。 */
    public List<VolunteerSanction> activeSanctions(Long volunteerId) {
        if (volunteerId == null) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        return sanctionMapper.selectList(Wrappers.<VolunteerSanction>lambdaQuery()
                .eq(VolunteerSanction::getVolunteerId, volunteerId)
                .eq(VolunteerSanction::getStatus, VolunteerSanction.STATUS_ACTIVE)
                .le(VolunteerSanction::getEffectiveTime, now)
                .and(w -> w.isNull(VolunteerSanction::getExpireTime)
                        .or().gt(VolunteerSanction::getExpireTime, now))
                .orderByDesc(VolunteerSanction::getId));
    }

    /**
     * 取一条正在挡住该能力域的处置；没有则 {@code null}。<b>快照读</b>，只给 {@link #isRestricted} 用。
     *
     * <p>⚠️ <b>这段谓词与 {@link VolunteerSanctionMapper#selectBlockingForShare} 是同一份口径的两个副本</b>
     * （一份快照读、一份当前读）。改判定条件——生效时刻、到期、{@code ALL} 蕴含关系、排序——
     * <b>两处必须一起改</b>，只改一处会让「展示说没被限制、闸门却拦下」这种自相矛盾出现在同一个页面上。
     * 之所以没合并成一个：闸门那条必须是带 {@code FOR SHARE} 的手写 SQL，而本方法用条件构造器
     * （{@code isRestricted} 可在事务外调用，不能加锁）。</p>
     */
    private VolunteerSanction findBlocking(Long volunteerId, int scope) {
        if (volunteerId == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        return sanctionMapper.selectOne(Wrappers.<VolunteerSanction>lambdaQuery()
                .eq(VolunteerSanction::getVolunteerId, volunteerId)
                .eq(VolunteerSanction::getStatus, VolunteerSanction.STATUS_ACTIVE)
                // 「拒绝使用本程序」蕴含所有能力域
                .in(VolunteerSanction::getScope, scope, SanctionScope.ALL)
                .le(VolunteerSanction::getEffectiveTime, now)
                .and(w -> w.isNull(VolunteerSanction::getExpireTime)
                        .or().gt(VolunteerSanction::getExpireTime, now))
                // 多条并存时取【管得最久】的那条来报错——报一个更早的解除时间是误导。
                // 【expire_time IS NULL 必须排在最前】NULL 表示「不设期限」即最重，
                // 而 MySQL 的 ORDER BY ... DESC 把 NULL 排在最后，直接 orderByDesc 会
                // 挑中一条有期限的，于是永久处置被报成「至 X 自动解除」——正好说反。
                .last("ORDER BY expire_time IS NULL DESC, expire_time DESC LIMIT 1"));
    }
}
