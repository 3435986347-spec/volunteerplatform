package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerContactView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.constant.PairFlow;
import com.hengde.donate.dao.DonatePairMappers.DonatePairProjectMapper;
import com.hengde.donate.dao.DonatePairMappers.DonatePairRecordMapper;
import com.hengde.donate.dto.PairDTOs;
import com.hengde.donate.entity.DonatePairProject;
import com.hengde.donate.entity.DonatePairRecord;
import com.hengde.donate.event.PairCancelledEvent;
import com.hengde.donate.event.PairEstablishedEvent;
import com.hengde.donate.vo.PairVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结对登记的全过程（Row 10，V3 结对批）：志愿者登记 → 协会确认成立 → 出证；可撤回、可取消。
 *
 * <p><b>本批不碰支付</b>：登记的是「我认捐多少」。「结对成立」由协会确认——钱怎么收在捐款批，
 * 而《协会待确认清单-v3》⑨ 的默认口径是<b>结对成立即出证</b>，故成立那一刻发事件、honor 出证。</p>
 *
 * <p><b>同一个人在同一个项目上的写动作靠一把行锁排队</b>：登记 / 撤回 / 确认 / 取消都先以当前读
 * 锁住那条「活」登记（{@code uk_active_pair} 唯一键，只锁一行）。「一人一项目至多一条有效登记」
 * 由生成列唯一键兜底，不靠先查再插。</p>
 *
 * @author hengde
 */
@Service
public class PairService {

    private DonatePairRecordMapper recordMapper;
    private DonatePairProjectMapper projectMapper;
    private PairProjectService projectService;
    private VolunteerQueryService volunteerQueryService;
    private ApplicationEventPublisher eventPublisher;
    private TransactionTemplate transactionTemplate;
    private RedissonClient redissonClient;

    /** 登记锁前缀（与 donate 域已有的 {@code lock:coupon-grant:request:} 互不抢占）。 */
    private static final String REGISTER_LOCK_PREFIX = "lock:pair:register:";

    @Autowired
    public void setRecordMapper(DonatePairRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    @Autowired
    public void setProjectMapper(DonatePairProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Autowired
    public void setProjectService(PairProjectService projectService) {
        this.projectService = projectService;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Autowired
    public void setTransactionTemplate(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    private DonationService donationService;

    @Autowired
    public void setDonationService(DonationService donationService) {
        this.donationService = donationService;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    // ================= 志愿者端 =================

    /**
     * 登记结对（Row 10「指定金额 / 全款」）。
     *
     * <p><b>资格校验在事务外</b>：事务里的第一条语句必须是那条当前读的锁——普通读会把 RR 读视图
     * 定死在别人提交之前，后面的复核就会读到过期状态（微心愿批的认领用例撞出过这一条）。</p>
     */
    public PairVOs.PairRecord register(Long projectId, Long volunteerId, PairDTOs.Register dto) {
        if (volunteerId == null) {
            throw new BusinessException("志愿者不能为空");
        }
        if (!volunteerQueryService.filterActiveRegistered(List.of(volunteerId)).contains(volunteerId)) {
            throw new BusinessException("结对登记需先完成志愿者实名注册");
        }
        // 按「项目 + 人」串行化，锁在事务之外：唯一键 uk_active_pair 能保证不重复，却保证不了不死锁——
        // 那条登记【还不存在】，于是并发请求的 selectActiveForUpdate 都在同一段间隙上取锁，
        // 随后各自的 INSERT 又要在这段间隙里取插入意向锁，互等成环（连点 6 次的用例当场撞出死锁）。
        // 与卷批「同一 requestId 的批量发卷并发重放」同一形状，修法也一样：先串行化，再进事务
        return DistributedLockSupport.runLocked(redissonClient, REGISTER_LOCK_PREFIX + projectId + ":" + volunteerId,
                () -> transactionTemplate.execute(s -> doRegister(projectId, volunteerId, dto)));
    }

    /**
     * 登记的事务体。<b>不对「还不存在的那条登记」做当前读</b>——对不存在的键加锁会在唯一键上留间隙锁，
     * 同项目下不同的人并发登记时互等成环（压测撞出来的死锁）。重复登记由 {@code uk_active_pair} 挡，
     * 撞键之后再用当前读把赢家读回来、据此给出说得清的提示。
     */
    private PairVOs.PairRecord doRegister(Long projectId, Long volunteerId, PairDTOs.Register dto) {
        DonatePairRecord live = recordMapper.selectActive(projectId, volunteerId);
        if (live != null) {
            throw new BusinessException(alreadyMessage(live));
        }
        DonatePairProject project = projectService.requireOpen(projectId);
        BigDecimal amount = resolveAmount(project, dto);
        DonatePairRecord r = new DonatePairRecord();
        r.setProjectId(projectId);
        r.setVolunteerId(volunteerId);
        r.setAmount(amount);
        r.setAmountType(dto != null && Objects.equals(dto.getAmountType(), PairFlow.AMOUNT_FULL)
                ? PairFlow.AMOUNT_FULL : PairFlow.AMOUNT_PARTIAL);
        r.setStatus(PairFlow.PAIR_REGISTERED);
        r.setRegisterTime(LocalDateTime.now());
        r.setRemark(dto == null ? null : trimToNull(dto.getRemark()));
        try {
            recordMapper.insert(r);
        } catch (DuplicateKeyException e) {
            // 撞了唯一键：拿当前读把赢家读回来，才说得出是「待确认」还是「已结对」
            DonatePairRecord winner = recordMapper.selectActiveForShare(projectId, volunteerId);
            throw new BusinessException(winner == null ? "你已经登记过这个项目，正在等协会确认"
                    : alreadyMessage(winner));
        }
        return PairProjectService.toRecordVO(r, project.getTitle());
    }

    /**
     * 本人撤回登记（<b>仅「待确认」可撤</b>）。已经成立的结对要由协会取消——
     * 成立那一刻已经出了一张盖章证书，撤销它是协会的动作，不该由本人一点了事。
     */
    public void withdraw(Long projectId, Long volunteerId) {
        // 先用普通读找那条登记：没有就直接回绝，【不去锁一个不存在的键】——那会留下间隙锁，
        // 与同项目下别人的登记 INSERT 互等成环（压测里「你没有登记这个项目」跑了 59 次，
        // 每一次原本都在制造这种间隙锁）
        DonatePairRecord live = recordMapper.selectActive(projectId, volunteerId);
        if (live == null) {
            throw new BusinessException("你没有登记这个项目");
        }
        // 捐款批起：与这条结对的付款持同一把锁。付过钱的不许本人撤回（退钱要走协会取消 + 原路退款）；
        // 有待支付的，先关交易单，关不掉（刚付）就放弃撤回
        DistributedLockSupport.runLocked(redissonClient, DonationService.pairLockKey(live.getId()), () -> {
            if (donationService.hasPaid(live.getId())) {
                throw new BusinessException("这条结对已经付过款，如需取消请联系协会，款项会原路退回");
            }
            Long awaiting = donationService.closeAwaitingForPair(live.getId());
            transactionTemplate.execute(s -> {
                doWithdraw(live);
                if (awaiting != null) {
                    donationService.cancelAwaiting(awaiting, "结对已撤回");
                }
                return null;
            });
            return null;
        });
    }

    private void doWithdraw(DonatePairRecord live) {
        // 存在就按主键取当前读：只锁这一行，且读到的是最新值（上面那次是快照读）
        DonatePairRecord locked = recordMapper.selectByIdForUpdate(live.getId());
        if (locked == null) {
            throw new BusinessException("你没有登记这个项目");
        }
        if (!Objects.equals(locked.getStatus(), PairFlow.PAIR_REGISTERED)) {
            throw new BusinessException("结对已经成立，如需取消请联系协会");
        }
        endRecord(locked.getId(), PairFlow.PAIR_REGISTERED, null, "本人撤回");
    }

    /** 我的结对（Row 34 结对中心的记录部分；跨项目聚合在收尾批）。 */
    public PageResult<PairVOs.PairRecord> myPairs(Long volunteerId, PageQuery query) {
        IPage<DonatePairRecord> page = recordMapper.selectPage(query.toPage(),
                Wrappers.<DonatePairRecord>lambdaQuery()
                        .eq(DonatePairRecord::getVolunteerId, volunteerId)
                        .orderByDesc(DonatePairRecord::getId));
        Map<Long, DonatePairProject> projects = projectService.byIds(page.getRecords().stream()
                .map(DonatePairRecord::getProjectId).collect(Collectors.toSet()));
        return PageResult.of(page.convert(r -> {
            DonatePairProject p = projects.get(r.getProjectId());
            return PairProjectService.toRecordVO(r, p == null ? null : p.getTitle());
        }));
    }

    /**
     * 结对中心详情（Row 34）：这条结对 + 为它付过的每一笔款（含发票字段）。
     * 不是本人的与不存在返回同一句话，防按 id 枚举。
     */
    public PairVOs.PairCenter myPairDetail(Long pairRecordId, Long volunteerId) {
        DonatePairRecord r = pairRecordId == null ? null : recordMapper.selectById(pairRecordId);
        if (r == null || !Objects.equals(r.getVolunteerId(), volunteerId)) {
            throw new BusinessException("结对记录不存在");
        }
        DonatePairProject p = projectService.byIds(java.util.Set.of(r.getProjectId())).get(r.getProjectId());
        PairVOs.PairCenter vo = new PairVOs.PairCenter();
        vo.setRecord(PairProjectService.toRecordVO(r, p == null ? null : p.getTitle()));
        java.math.BigDecimal paid = r.getPaidAmount() == null ? java.math.BigDecimal.ZERO : r.getPaidAmount();
        vo.setRemainingAmount(Objects.equals(r.getStatus(), PairFlow.PAIR_CANCELLED) || r.getAmount() == null
                ? java.math.BigDecimal.ZERO.setScale(2) : r.getAmount().subtract(paid).max(java.math.BigDecimal.ZERO));
        vo.setDonations(donationService.listOfPairRecord(r.getId()));
        return vo;
    }

    // ================= 管理端 =================

    /**
     * 确认结对成立：登记 CAS 待确认 → 成立，项目认捐额累加，达标则置「已结对」，最后发事件出证。
     *
     * <p><b>顺序是承重的</b>：先累加认捐额（它自带「项目仍在进行中」的条件），再 CAS 登记行；
     * 反过来的话，CAS 成功而累加失败时要么账不平、要么得回滚一次已经成立的结对。
     * 事件在事务提交后才被 honor 处理（{@code AFTER_COMMIT}），所以这里发出去是安全的。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void establish(Long pairRecordId, Long adminId) {
        requireAdmin(adminId);
        DonatePairRecord r = recordMapper.selectByIdForUpdate(pairRecordId);
        if (r == null) {
            throw new BusinessException("结对登记不存在");
        }
        if (!Objects.equals(r.getStatus(), PairFlow.PAIR_REGISTERED)) {
            throw new BusinessException("这条登记当前不能确认（" + PairFlow.pairLabel(r.getStatus()) + "）");
        }
        LocalDateTime now = LocalDateTime.now();
        int added = projectMapper.addPledged(r.getProjectId(), r.getAmount(), now, PairFlow.PROJECT_OPEN);
        if (added != 1) {
            // 两种可能：项目已不在进行中，或另一笔结对刚被确认、剩余缺口已经不够这一笔了。
            // 两者都要人去看一眼，故不分开报——分开报要再查一次库，而查到的还是刚刚变过的值
            throw new BusinessException("项目不在进行中，或剩余缺口已不足（可能另一笔结对刚被确认），不能确认结对");
        }
        int rows = recordMapper.update(null, Wrappers.<DonatePairRecord>lambdaUpdate()
                .eq(DonatePairRecord::getId, pairRecordId)
                .eq(DonatePairRecord::getStatus, PairFlow.PAIR_REGISTERED)
                .set(DonatePairRecord::getStatus, PairFlow.PAIR_ESTABLISHED)
                .set(DonatePairRecord::getEstablishedTime, now)
                .set(DonatePairRecord::getUpdateTime, now));
        if (rows != 1) {
            throw new BusinessException("这条登记的状态已变化，请刷新后重试");
        }
        projectService.markPairedIfFull(r.getProjectId());
        // 证书归 honor，而依赖方向是 honor → donate，不能反向调用：发事件，honor 在提交后出证
        eventPublisher.publishEvent(new PairEstablishedEvent(pairRecordId, r.getProjectId(), r.getVolunteerId()));
    }

    /**
     * 协会取消一条结对登记（待确认或已成立均可，原因必填——结对人会看到）。
     *
     * <p>取消<b>已成立</b>的那一条时还要做两件事：把认捐额退回去（项目若因此不再满额，回到「进行中」），
     * 以及发事件让 honor 把那张已经发出的捐赠证书撤销——留着的话，那是一张为不存在的结对背书的盖章证书。</p>
     */
    public void cancel(Long pairRecordId, String reason, Long adminId) {
        requireAdmin(adminId);
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("请填写取消原因——结对人会看到");
        }
        if (reason.trim().length() > 512) {
            throw new BusinessException("取消原因不超过 512 字");
        }
        // 捐款批起：与这条结对的付款持同一把锁。待支付的先关交易单（关不掉＝刚付，放弃取消、让人刷新再看）；
        // 取消提交【之后】再把已到账的钱逐笔原路退——先退后取消，退款成功而取消回滚就是钱退了结对还在
        DistributedLockSupport.runLocked(redissonClient, DonationService.pairLockKey(pairRecordId), () -> {
            Long awaiting = donationService.closeAwaitingForPair(pairRecordId);
            transactionTemplate.execute(s -> {
                doCancel(pairRecordId, reason.trim(), adminId);
                if (awaiting != null) {
                    donationService.cancelAwaiting(awaiting, "结对已取消");
                }
                return null;
            });
            donationService.refundAllOfPair(pairRecordId, reason.trim(), adminId);
            return null;
        });
    }

    private void doCancel(Long pairRecordId, String reason, Long adminId) {
        DonatePairRecord r = recordMapper.selectByIdForUpdate(pairRecordId);
        if (r == null) {
            throw new BusinessException("结对登记不存在");
        }
        if (Objects.equals(r.getStatus(), PairFlow.PAIR_CANCELLED)) {
            throw new BusinessException("这条登记已经取消过了");
        }
        boolean established = Objects.equals(r.getStatus(), PairFlow.PAIR_ESTABLISHED);
        if (established) {
            int back = projectMapper.subtractPledged(r.getProjectId(), r.getAmount(), LocalDateTime.now());
            if (back != 1) {
                throw new BusinessException("项目认捐额异常（回退后会为负），请联系管理员核查");
            }
        }
        endRecord(pairRecordId, r.getStatus(), adminId, reason);
        if (established) {
            projectService.reopenIfNotFull(r.getProjectId());
            eventPublisher.publishEvent(new PairCancelledEvent(pairRecordId, r.getVolunteerId(), reason));
        }
    }

    /** 后台结对登记列表（按项目 / 状态筛选，带结对人姓名与电话）。 */
    public PageResult<PairVOs.PairRecord> listForAdmin(PageQuery query, Long projectId, Integer status) {
        IPage<DonatePairRecord> page = recordMapper.selectPage(query.toPage(),
                Wrappers.<DonatePairRecord>lambdaQuery()
                        .eq(projectId != null, DonatePairRecord::getProjectId, projectId)
                        .eq(status != null, DonatePairRecord::getStatus, status)
                        .orderByDesc(DonatePairRecord::getId));
        Map<Long, DonatePairProject> projects = projectService.byIds(page.getRecords().stream()
                .map(DonatePairRecord::getProjectId).collect(Collectors.toSet()));
        Set<Long> volunteerIds = page.getRecords().stream()
                .map(DonatePairRecord::getVolunteerId).collect(Collectors.toSet());
        Map<Long, VolunteerContactView> contacts = volunteerIds.isEmpty() ? Map.of()
                : volunteerQueryService.listContactsByIds(volunteerIds);
        return PageResult.of(page.convert(r -> {
            DonatePairProject p = projects.get(r.getProjectId());
            PairVOs.PairRecord vo = PairProjectService.toRecordVO(r, p == null ? null : p.getTitle());
            vo.setVolunteerId(r.getVolunteerId());
            VolunteerContactView c = contacts.get(r.getVolunteerId());
            vo.setVolunteerName(c == null ? null : c.realName());
            vo.setVolunteerPhone(c == null ? null : c.phone());
            return vo;
        }));
    }

    // ================= 内部 =================

    /** 登记行 CAS 「待确认 / 已成立」→ 已取消。调用方已持有这条登记的行锁。 */
    private void endRecord(Long id, int from, Long adminId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        int rows = recordMapper.update(null, Wrappers.<DonatePairRecord>lambdaUpdate()
                .eq(DonatePairRecord::getId, id)
                .eq(DonatePairRecord::getStatus, from)
                .set(DonatePairRecord::getStatus, PairFlow.PAIR_CANCELLED)
                .set(DonatePairRecord::getCancelTime, now)
                .set(DonatePairRecord::getCancelBy, adminId)
                .set(DonatePairRecord::getCancelReason, reason)
                .set(DonatePairRecord::getUpdateTime, now));
        if (rows != 1) {
            throw new BusinessException("这条登记的状态已变化，请刷新后重试");
        }
    }

    /**
     * 认捐金额：「全款」按项目当前缺口算，「指定金额」用传入值。
     *
     * <p><b>不允许超过缺口</b>：认捐额是要拿去和受助金额比进度的，超额登记会让进度越过 100%，
     * 而多出来的那部分并没有对应的受助需求。</p>
     */
    private static BigDecimal resolveAmount(DonatePairProject project, PairDTOs.Register dto) {
        BigDecimal remaining = project.getTargetAmount().subtract(project.getPledgedAmount());
        if (remaining.signum() <= 0) {
            throw new BusinessException("这个项目的认捐额已满，看看别的项目吧");
        }
        if (dto != null && Objects.equals(dto.getAmountType(), PairFlow.AMOUNT_FULL)) {
            return remaining;
        }
        if (dto == null || dto.getAmount() == null || dto.getAmount().signum() <= 0) {
            throw new BusinessException("请填写认捐金额，或选择全款");
        }
        if (dto.getAmount().compareTo(remaining) > 0) {
            throw new BusinessException("认捐金额超过项目剩余缺口（还差 " + remaining.stripTrailingZeros().toPlainString() + " 元）");
        }
        // 金额落库是 DECIMAL(12,2)：这里先对齐小数位，否则「登记成功」的返回体显示 200，
        // 而库里与之后每一次查询都是 200.00——同一笔钱在两个地方长得不一样
        return dto.getAmount().setScale(2, RoundingMode.HALF_UP);
    }

    private static String alreadyMessage(DonatePairRecord live) {
        return Objects.equals(live.getStatus(), PairFlow.PAIR_ESTABLISHED)
                ? "你已经与这个项目结对了" : "你已经登记过这个项目，正在等协会确认";
    }

    private static void requireAdmin(Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
