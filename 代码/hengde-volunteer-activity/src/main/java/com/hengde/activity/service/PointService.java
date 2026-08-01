package com.hengde.activity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hengde.activity.constant.PointSourceType;
import com.hengde.activity.dao.PointRecordMapper;
import com.hengde.activity.dto.PointAdjustDTO;
import com.hengde.activity.entity.PointRecord;
import com.hengde.activity.vo.PointRecordVO;
import com.hengde.activity.vo.PointSummaryVO;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.auth.vo.VolunteerFlagInfoView;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 积分账本服务（V2 第 1 批）。积分的写入与查询统一走这里。
 *
 * <p><b>为什么建账本</b>：此前积分只落在 {@code activity_attendance.points_award} 一个字段上，
 * 无法表达「已用积分」、无法容纳勋章/奖惩/兑换等多来源、也无法审计「这分怎么来的」。
 * 本服务建立后，{@code point_record} 是积分唯一事实来源，各业务写积分一律经此入账。</p>
 *
 * <p><b>幂等是硬要求</b>——积分等同权益，重复入账不可接受。防线有三：
 * ① 库层唯一约束（有单据的来源走 {@code uk_source(source_type, source_id)}，手工调整走
 * {@code uk_request_id}）；② 入账前先查是否已存在；③ <b>命中后复核载荷</b>。
 * 前两条缺一不可：查询挡掉常规重复（并给出友好的幂等语义，而非抛异常中断调用方事务），
 * 唯一约束兜住并发穿透。</p>
 *
 * <p>第 ③ 条同样不可省。只判「键是否存在」会把<b>键冲突伪装成成功</b>——同一个 requestId 先给 A 加 10、
 * 又被误用于给 B 加 20，第二次会返回成功和 B 的余额，而那 20 分根本没入账，调用方与前端都察觉不到。
 * 故命中已有流水时必须比对志愿者/金额/来源，一致才算重放，不一致一律抛「积分入账冲突」。</p>
 *
 * <p>手工调整之所以要额外的 {@code request_id}：它没有天然单据，{@code source_id} 只能为 null，
 * 而 MySQL 唯一索引视多个 NULL 互不相同，{@code uk_source} 对它形同虚设——双击/超时重试/网关重放
 * 都会实打实地重复加减分。</p>
 *
 * <p><b>并发</b>：{@link #record} 是<b>无状态追加</b>——<b>不以余额参与入账决策</b>（余额只在最后取来回给调用方）、
 * 也不改既有行，故天然并发安全，无需加锁。
 * 这也是本表不冗余存「变动后余额」的原因：入账必须与调用方业务变更同事务（积分与考勤同成同败），
 * 而项目既有约定是「锁在事务之外获取」（见 {@code EnrollmentService}），二者不可兼得——
 * 锁若落在调用方事务内，另一线程可能在本线程解锁后、提交前拿到锁而读到旧余额。改为纯 SUM 求余额后，
 * 该竞态整类消失。</p>
 *
 * <p>唯一需要「读余额再决策」的是 {@link #adjust}（扣分不得扣成负数），那里在<b>事务之外</b>
 * 按志愿者维度加 Redisson 锁（复用 common 的 {@link DistributedLockSupport}，前缀
 * {@code lock:point:volunteer:}，与既有 {@code lock:enroll:} / {@code lock:group:} 并列互不抢占）。</p>
 *
 * <p>依赖按项目约定用 setter 注入。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class PointService {

    /** 志愿者维度锁前缀，与 enroll/group 的前缀并列，互不抢占 */
    private static final String LOCK_KEY_PREFIX = "lock:point:volunteer:";

    /** {@code point_record.remark} 的列宽（字符数），超长在此截断而非让 DB 抛错 */
    private static final int REMARK_MAX_LENGTH = 512;

    private PointRecordMapper pointRecordMapper;
    private RedissonClient redissonClient;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setPointRecordMapper(PointRecordMapper pointRecordMapper) {
        this.pointRecordMapper = pointRecordMapper;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    /**
     * 记一笔积分流水（入账为正、出账为负），幂等。
     *
     * <p><b>调用方须在自己的事务内调用</b>，以保证「业务状态变更」与「积分入账」同成同败
     * （如 {@code grantPoints} 的考勤 CAS 与本次入账）。本方法不自开事务。</p>
     *
     * <p>幂等语义：同 {@code (sourceType, sourceId)}（或同 {@code requestId}）<b>且载荷一致</b>的重复调用
     * 跳过入账并返回余额（同下方口径），不抛异常——调用方多为审核/发放路径，重放时不应因「已入过账」而整体失败。
     * 但<b>键相同而载荷不同会抛 {@link BusinessException}</b>，见 {@code confirmReplayAndGetBalance}。
     * {@code sourceId} 与 {@code requestId} 皆为 null 时不做幂等判定，每次都记一笔。</p>
     *
     * <p><b>返回值的确切含义</b>：余额由 {@code SUM(change_amount)} 现算，走的是普通快照读。
     * 在调用方事务内（RR 隔离级别），本次插入对自己一定可见，但<b>其它事务在本事务快照建立之后提交的流水
     * 不可见</b>——因此事务内拿到的余额只是「本事务视角的余额」，不保证等于全局最新值。
     * 这不是缺陷而是隔离级别的必然：事务内本就不存在「全局当前值」这个概念。要拿到权威余额，
     * 只能在事务提交后单独调 {@link #balanceOf}。</p>
     *
     * <p>之所以不改成 {@code FOR UPDATE} 当前读：那会把该志愿者<b>全部历史流水行</b>锁到事务提交，
     * 行数随时间无限增长，代价与死锁面都不可接受，而换来的仅仅是一个调用方并不需要的数字——
     * 三条生产链路（发放 / 补录 / 修正）都忽略此返回值。真正把余额展示给人的是
     * {@link #adjust}，它在事务之外执行、每条语句各自提交，读到的就是最新值。</p>
     *
     * @param volunteerId  志愿者 id
     * @param changeAmount 变动值，正=入账 负=出账，不可为 0
     * @param sourceType   来源类型，见 {@link PointSourceType}
     * @param sourceId     来源单据 id；手工调整传 null
     * @param remark       说明（对志愿者展示）
     * @param operatorType 操作方 0系统/1管理员
     * @param operatorId   操作人 id，系统操作传 null
     * @return <b>调用方事务快照可见的余额，非权威值</b>——详见下方「返回值的确切含义」
     */
    public int record(Long volunteerId, int changeAmount, int sourceType, Long sourceId,
                      String remark, int operatorType, Long operatorId) {
        return record(volunteerId, changeAmount, sourceType, sourceId, null, remark, operatorType, operatorId);
    }

    /**
     * 记一笔积分流水，带显式幂等键。用于<b>没有天然单据</b>的来源（当前只有管理员手工调整）。
     *
     * <p>其余来源请用不带 {@code requestId} 的重载——它们各自的单据 id 就是天然幂等键。</p>
     *
     * @param requestId 幂等键；为 null 时退化为按 {@code sourceId} 判重
     * @see #record(Long, int, int, Long, String, int, Long)
     */
    public int record(Long volunteerId, int changeAmount, int sourceType, Long sourceId, String requestId,
                      String remark, int operatorType, Long operatorId) {
        if (volunteerId == null) {
            throw new BusinessException("志愿者不能为空");
        }
        if (changeAmount == 0) {
            throw new BusinessException("积分变动值不能为 0");
        }
        String key = StringUtils.hasText(requestId) ? requestId : null;
        // 统一在此截断：remark 列 512，而上游拼进来的活动名、修正理由等长度不受本服务控制，
        // 不截断会在严格模式下抛 data too long 把整个调用方事务带崩（发积分/审核都会连带失败）。
        // 截断后的值同时用于下面的载荷比对，保证「写进去的」与「比对的」是同一个串。
        String trimmedRemark = truncateRemark(remark);
        // 先查幂等：已入过账则复核载荷后跳过。此处不加锁——并发穿透由下面的唯一约束兜底
        PointRecord existing = key != null
                ? pointRecordMapper.selectByRequestId(key)
                : (sourceId != null ? pointRecordMapper.selectBySource(sourceType, sourceId) : null);
        if (existing != null) {
            return confirmReplayAndGetBalance(existing, volunteerId, changeAmount, sourceType, sourceId,
                    key, trimmedRemark, operatorType, operatorId);
        }
        PointRecord record = new PointRecord();
        record.setVolunteerId(volunteerId);
        record.setChangeAmount(changeAmount);
        record.setSourceType(sourceType);
        record.setSourceId(sourceId);
        record.setRequestId(key);
        record.setRemark(trimmedRemark);
        record.setOperatorType(operatorType);
        record.setOperatorId(operatorId);
        try {
            pointRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 唯一约束兜住并发穿透：两个线程同时过了上面的存在性判断，只有一个能插入成功。
            // 同样要复核载荷——用当前读取回冲突行（快照读可能看不到对方刚提交的行，详见 mapper 注释）。
            // 用共享锁而非排他锁：报重复键时 InnoDB 已给冲突行加了 S 锁，多个 loser 再抢 X 锁会互等成死锁。
            PointRecord conflict = key != null
                    ? pointRecordMapper.selectByRequestIdForShare(key)
                    : pointRecordMapper.selectBySourceForShare(sourceType, sourceId);
            if (conflict != null) {
                return confirmReplayAndGetBalance(conflict, volunteerId, changeAmount, sourceType, sourceId,
                        key, trimmedRemark, operatorType, operatorId);
            }
            // 取不回冲突行，无从判断是否同一笔——宁可报错也不能假装成功
            log.error("[POINT] 唯一键冲突但无法取回冲突流水 volunteerId={} sourceType={} sourceId={} requestId={}",
                    volunteerId, sourceType, sourceId, key, e);
            throw new BusinessException("积分入账冲突，请稍后重试");
        }
        return pointRecordMapper.sumBalance(volunteerId);
    }

    /**
     * 手工调整的目标校验：必须是**存在且已实名**的志愿者。
     *
     * <p>{@code point_record} 没有外键（跨模块表，本项目一贯不建跨域外键），若不校验，
     * 给一个不存在或已被删除的 id 加分会生成<b>孤儿流水</b>并返回成功——账本里凭空多出一笔谁也认领不了的积分。
     * 要求已实名则是因为游客尚未完成注册，给游客调分没有业务含义。</p>
     *
     * <p><b>停用 / 注销账号仍允许调整</b>，这是有意为之：手工调整的主要用途就是纠正历史账目，
     * 若按「仅正常状态」拦截，被停用者账上的错账就永远改不回来了；而积分对停用账号没有实际效用
     * （登录已被 {@code checkVolunteerEnabled} 拦下，V3 兑换另有状态校验）。仅记一条警告日志备查。</p>
     */
    private void requireAdjustableVolunteer(Long volunteerId) {
        VolunteerFlagInfoView info = volunteerQueryService.getFlagInfo(volunteerId);
        if (info == null) {
            throw new BusinessException("志愿者不存在");
        }
        if (!info.registered()) {
            throw new BusinessException("该志愿者尚未实名注册，不能调整积分");
        }
        if (!volunteerQueryService.isActive(volunteerId)) {
            log.warn("[POINT] 对非正常状态账号手工调整积分 volunteerId={}（允许，用于纠正历史账目）", volunteerId);
        }
    }

    /**
     * 命中已有流水时的复核：<b>载荷相同才算重放</b>，否则报幂等冲突。
     *
     * <p>只看「键是否存在」是不够的。若同一个 {@code requestId} 先给 A 加了 10 分，之后被误用于给 B 加 20 分，
     * 仅判存在会让第二次<b>返回成功并回 B 的余额，而那 20 分根本没入账</b>——调用方与前端都无从察觉。
     * 积分是权益，这种「静默吞掉一笔账」比直接报错危险得多。</p>
     *
     * <p><b>两条路径比对的字段不同，这是有意的：</b></p>
     * <ul>
     *   <li><b>{@code requestId} 路径</b>（手工调整）额外比对 {@code remark}（＝调整理由）与操作人。
     *       幂等键由前端为「这一次调整」生成，它代表的是一个完整的操作意图；同人同额但换了理由或换了管理员，
     *       就是另一次调整，若放行则账本会留着第一次的理由和操作人，审计链对不上——而 {@code reason}
     *       和操作人恰恰是手工调分唯一的追溯依据。</li>
     *   <li><b>{@code sourceId} 路径</b>（活动/补录/修正）只比对志愿者/金额/来源。这类流水的身份由单据本身
     *       确定，{@code remark} 是展示文案（活动可能改名）、操作人可能是另一个管理员重试同一张单据，
     *       把它们纳入比对会把正常重放误判成冲突。</li>
     * </ul>
     *
     * @return 载荷一致（真重放）时返回本事务快照可见的余额（并发重放下可能尚不含对方刚提交的那笔）
     * @throws BusinessException 载荷不一致
     */
    private int confirmReplayAndGetBalance(PointRecord existing, Long volunteerId, int changeAmount,
                                           int sourceType, Long sourceId, String requestId,
                                           String remark, int operatorType, Long operatorId) {
        boolean samePayload = volunteerId.equals(existing.getVolunteerId())
                && Integer.valueOf(changeAmount).equals(existing.getChangeAmount())
                && Integer.valueOf(sourceType).equals(existing.getSourceType())
                && Objects.equals(sourceId, existing.getSourceId());
        if (samePayload && requestId != null) {
            samePayload = Objects.equals(remark, existing.getRemark())
                    && Integer.valueOf(operatorType).equals(existing.getOperatorType())
                    && Objects.equals(operatorId, existing.getOperatorId());
        }
        if (!samePayload) {
            log.error("[POINT] 幂等键载荷冲突：既有流水 id={} volunteerId={} amount={} sourceType={} sourceId={} "
                            + "operatorId={} remark={}；本次 volunteerId={} amount={} sourceType={} sourceId={} "
                            + "operatorId={} remark={} requestId={}",
                    existing.getId(), existing.getVolunteerId(), existing.getChangeAmount(),
                    existing.getSourceType(), existing.getSourceId(), existing.getOperatorId(), existing.getRemark(),
                    volunteerId, changeAmount, sourceType, sourceId, operatorId, remark, requestId);
            throw new BusinessException("积分入账冲突：该幂等键已用于另一笔不同的积分变动");
        }
        log.info("[POINT] 同一笔流水重放，跳过重复入账 volunteerId={} sourceType={} sourceId={} requestId={}",
                volunteerId, sourceType, sourceId, requestId);
        return pointRecordMapper.sumBalance(volunteerId);
    }

    /**
     * remark 超长安全截断（末位留省略号示意被截）。完整原文另存于各自的业务单据表。
     *
     * <p><b>按 code point 而非 {@code String.length()} 截断</b>：Java 的 length 数的是 UTF-16 码元，
     * 一个 emoji（增补平面字符）占 2 个码元，从中间切会切断代理对，留下半个字符——存进 utf8mb4 后
     * 展示成乱码或问号，还会破坏该行的搜索匹配。而 MySQL 的 VARCHAR(512) 数的本就是字符（code point），
     * 两边的计数单位必须对齐。志愿者填的理由、活动名里出现 emoji 完全是常态。</p>
     */
    private String truncateRemark(String remark) {
        if (remark == null) {
            return null;
        }
        int codePoints = remark.codePointCount(0, remark.length());
        if (codePoints <= REMARK_MAX_LENGTH) {
            return remark;
        }
        // 留一个字符的位置给省略号，且按 code point 定位切点，保证不切断代理对
        int end = remark.offsetByCodePoints(0, REMARK_MAX_LENGTH - 1);
        return remark.substring(0, end) + "…";
    }

    /**
     * 管理员手工调整积分。绕过活动发放公式，故强制填原因并落操作人审计。
     *
     * <p>扣分是本服务里唯一「读余额→再决策」的路径（不得扣成负数），存在检查与写入之间被并发插队的
     * 可能，故按志愿者维度加锁。本方法不在调用方事务内（控制器直接调用），锁得以正确地位于事务之外，
     * 符合项目「锁在事务之外获取」的约定。</p>
     *
     * <p><b>「不得为负」只约束手工扣分，不约束积分修正</b>，这是有意的不对称：手工调整是一次<b>主观决策</b>，
     * 余额不够时应当让管理员先看清余额再决定；而 {@link com.hengde.activity.service.ActivityChangeService}
     * 的积分修正是在<b>更正既成事实</b>（这场活动本就该发 5 分而不是 10 分），若因余额不足而拒绝，账本就会
     * 长期停留在错误值上——两害相权，宁可让余额短暂为负也要让账实相符。故修正走 {@link #record} 直接入账，
     * 不经本方法的余额检查。V3 积分兑换属「消费」，与手工扣分同类，届时应共用本方法的检查与锁。</p>
     *
     * <p>幂等由 {@code dto.requestId} 保证，详见 {@link PointAdjustDTO}。</p>
     *
     * @param dto     调整入参（志愿者/分值/原因/幂等键）
     * @param adminId 操作管理员 id，非空
     * @return 调整后余额
     */
    public int adjust(PointAdjustDTO dto, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        if (dto.getChangeAmount() == null || dto.getChangeAmount() == 0) {
            throw new BusinessException("调整分值不能为 0");
        }
        if (!StringUtils.hasText(dto.getReason())) {
            throw new BusinessException("请填写调整原因");
        }
        if (!StringUtils.hasText(dto.getRequestId())) {
            throw new BusinessException("缺少幂等键 requestId");
        }
        requireAdjustableVolunteer(dto.getVolunteerId());
        return DistributedLockSupport.runLocked(redissonClient, LOCK_KEY_PREFIX + dto.getVolunteerId(), () -> {
            // 重放判定先于余额检查：同一次调整的重放不该因「这次余额不够了」而报错，它本就不会再入账。
            // 直接交给 record —— 它会复核载荷，载荷不符时抛冲突而非假装成功。
            if (pointRecordMapper.selectByRequestId(dto.getRequestId()) != null) {
                return record(dto.getVolunteerId(), dto.getChangeAmount(), PointSourceType.MANUAL, null,
                        dto.getRequestId(), dto.getReason(), PointSourceType.OPERATOR_ADMIN, adminId);
            }
            // 扣分不得把余额扣成负数——手工扣分是主观决策，余额不足应让管理员重新判断（修正类流水不受此限，见上）
            if (dto.getChangeAmount() < 0) {
                int balance = pointRecordMapper.sumBalance(dto.getVolunteerId());
                if (balance + dto.getChangeAmount() < 0) {
                    throw new BusinessException("扣减后余额将为负数，当前余额 " + balance);
                }
            }
            return record(dto.getVolunteerId(), dto.getChangeAmount(), PointSourceType.MANUAL, null,
                    dto.getRequestId(), dto.getReason(), PointSourceType.OPERATOR_ADMIN, adminId);
        });
    }

    /**
     * 积分总览：总积分（累计获得）/ 已使用积分 / 当前余额，对应需求「积分中心：总积分、已使用积分」。
     *
     * <p><b>按来源分类而非按正负分类</b>：消费类来源（当前仅兑换）计入「已使用」，其余来源
     * （活动 / 修正 / 勋章 / 手工 / 奖惩）一律计入「累计获得」的<b>净额</b>。
     * 理由见 {@link PointSourceType#isConsumption}——积分修正产生的负数不是消费，
     * 按正负统计会让积分中心显示一笔并不存在的「已使用」。</p>
     *
     * <p>恒等式 {@code balance = totalEarned - totalSpent} 在此口径下依然成立。</p>
     *
     * @param volunteerId 志愿者 id
     * @return 总览，无流水时三项均为 0
     */
    public PointSummaryVO summary(Long volunteerId) {
        int earned = 0;
        int spent = 0;
        for (PointRecordMapper.PointSourceSumRow row : pointRecordMapper.selectSumsBySourceType(volunteerId)) {
            int total = row.getTotal() == null ? 0 : row.getTotal();
            if (PointSourceType.isConsumption(row.getSourceType())) {
                // 消费流水记的是负数，取反成正数展示；若有退款（正数）则自然冲减已使用额
                spent -= total;
            } else {
                earned += total;
            }
        }
        PointSummaryVO vo = new PointSummaryVO();
        vo.setTotalEarned(earned);
        vo.setTotalSpent(spent);
        vo.setBalance(earned - spent);
        return vo;
    }

    /**
     * 当前积分余额——<b>权威读法</b>，用于展示。
     *
     * <p>须在事务之外调用才是全局最新值；若在某个长事务内调用，同样受该事务快照限制
     * （原因见 {@link #record} 的「返回值的确切含义」）。</p>
     *
     * @param volunteerId 志愿者 id
     * @return 余额，无流水返回 0
     */
    public int balanceOf(Long volunteerId) {
        return pointRecordMapper.sumBalance(volunteerId);
    }

    /**
     * 批量取多人余额，供列表页避免 N+1。
     *
     * @param volunteerIds 志愿者 id 集合
     * @return volunteerId → 余额；集合为空或无流水者不在结果中，调用方按 0 处理
     */
    public Map<Long, Integer> batchBalance(Collection<Long> volunteerIds) {
        if (volunteerIds == null || volunteerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Integer> result = new HashMap<>();
        for (PointRecordMapper.PointBalanceRow row : pointRecordMapper.selectBalances(volunteerIds)) {
            result.put(row.getVolunteerId(), row.getBalance());
        }
        return result;
    }

    /**
     * 分页查积分明细，按发生时间倒序，可按来源类型与时间区间筛选。
     *
     * <p><b>排序按 {@code create_time} 而非 id</b>：V24 的历史回填把已发放的考勤积分补成流水，
     * 其 {@code create_time} 取自考勤的更新时间，与流水自增 id 的先后<b>并不一致</b>
     * （回填按 volunteer_id, attendance_id 顺序插入，而考勤的更新时间是乱序的）。
     * 按 id 排会让志愿者看到时间跳来跳去的明细。id 仅作同一时刻的次级排序保证稳定分页。</p>
     *
     * <p><b>搜索匹配 {@code remark}</b>（如「参加XX活动」「表彰加分」）：需求要的「明细带搜索框」，
     * 在单人明细语境下唯一有意义的检索对象就是这段说明文字——活动名称、修正原因、调整理由都写在里面。
     * 志愿者姓名/手机号不在此检索：两个端点都已按 {@code volunteerId} 限定到某一个人，
     * 跨志愿者查人应走 user 域的志愿者检索。</p>
     *
     * @param volunteerId 志愿者 id
     * @param query       分页参数
     * @param sourceType  按来源类型筛选，null=不筛
     * @param startTime   起始时间（含），null=不限
     * @param endTime     截止时间（含），null=不限
     * @param keyword     搜索词，匹配说明 {@code remark}；空白=不筛
     * @return 明细分页
     */
    public PageResult<PointRecordVO> pageRecords(Long volunteerId, PageQuery query, Integer sourceType,
                                                 LocalDateTime startTime, LocalDateTime endTime, String keyword) {
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        Page<PointRecord> page = query.toPage();
        pointRecordMapper.selectPage(page, Wrappers.<PointRecord>lambdaQuery()
                .eq(PointRecord::getVolunteerId, volunteerId)
                .eq(sourceType != null, PointRecord::getSourceType, sourceType)
                .ge(startTime != null, PointRecord::getCreateTime, startTime)
                .le(endTime != null, PointRecord::getCreateTime, endTime)
                .like(kw != null, PointRecord::getRemark, kw)
                .orderByDesc(PointRecord::getCreateTime)
                .orderByDesc(PointRecord::getId));
        List<PointRecordVO> vos = page.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 只按来源类型筛选的重载。 */
    public PageResult<PointRecordVO> pageRecords(Long volunteerId, PageQuery query, Integer sourceType) {
        return pageRecords(volunteerId, query, sourceType, null, null, null);
    }


    private PointRecordVO toVO(PointRecord r) {
        PointRecordVO vo = new PointRecordVO();
        vo.setId(r.getId());
        vo.setChangeAmount(r.getChangeAmount());
        vo.setSourceType(r.getSourceType());
        vo.setSourceTypeName(PointSourceType.labelOf(r.getSourceType()));
        vo.setRemark(r.getRemark());
        vo.setOperatorType(r.getOperatorType());
        vo.setOperatorId(r.getOperatorId());
        vo.setCreateTime(r.getCreateTime());
        return vo;
    }
}
