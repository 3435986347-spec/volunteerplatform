package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.constant.MallCouponGrantStatus;
import com.hengde.donate.constant.MallCouponType;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.dao.MallCouponGrantMapper;
import com.hengde.donate.dao.MallCouponMapper;
import com.hengde.donate.dao.MallGoodsMapper;
import com.hengde.donate.dao.MallGoodsSpecMapper;
import com.hengde.donate.dto.MallCouponGrantDTO;
import com.hengde.donate.dto.MallCouponSaveDTO;
import com.hengde.donate.entity.MallCoupon;
import com.hengde.donate.entity.MallCouponGrant;
import com.hengde.donate.entity.MallGoods;
import com.hengde.donate.entity.MallGoodsSpec;
import com.hengde.donate.vo.CouponGrantResultVO;
import com.hengde.donate.vo.MallCouponGrantVO;
import com.hengde.donate.vo.MallCouponVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 卷（Row 8 F「商品只能使用指定卷才能兑换」「发放卷功能、批量发卷功能，如指定商品兑换卷、积分满减卷」；
 * Row 8 C「我的卷」）。
 *
 * <p><b>三条规则取《协会待确认清单-v3》④ 的默认</b>：满减的「满」按积分算；一单一卷不叠加；
 * 有有效期、到期即失效——「已过期」按时间现算，不落库、不靠定时任务改状态位。</p>
 *
 * <p><b>发出去的卷快照条款</b>：用卷时一律读 {@link MallCouponGrant} 上的快照，不读卷定义的当前值。
 * 管理员事后改卷定义，只影响之后发的卷。</p>
 *
 * <p><b>「这张卷能不能用在这件商品上」只在 {@link #inapplicableReason} 一处判</b>——
 * 下单（{@link #quote}）与「可用的卷」列表（{@link #listUsableForSpec}）共用它，
 * 否则列表里显示可用、下单时却报不适用，两边迟早漂开。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class MallCouponService {

    /** 单次发卷的目标人数上限：再多就该拆批，一个事务里插上万行会把锁持有时间拉到不可接受。 */
    static final int MAX_GRANT_TARGETS = 1000;

    /**
     * 发卷按 requestId 串行化的锁前缀。<b>为什么要这把锁、为什么键是 requestId 而不是卷</b>见 {@link #grant}。
     * 与 {@code lock:point:volunteer:} 等既有前缀互不抢占。
     */
    public static final String GRANT_LOCK_PREFIX = "lock:coupon-grant:request:";

    /** 与 {@code MallCouponGrantDTO.requestId} 的 {@code @Pattern} 同一份字符集——服务层再挡一次，不只信注解。 */
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9:._-]{1,64}");

    private static final DateTimeFormatter MSG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 卷定义状态：启用。 */
    public static final int COUPON_ENABLED = 1;

    /** 卷定义状态：停用（只挡新发放，已发出的照常可用）。 */
    public static final int COUPON_DISABLED = 0;

    private MallCouponMapper couponMapper;
    private MallCouponGrantMapper grantMapper;
    private MallGoodsMapper goodsMapper;
    private MallGoodsSpecMapper specMapper;
    private VolunteerQueryService volunteerQueryService;
    private RedissonClient redissonClient;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setCouponMapper(MallCouponMapper couponMapper) {
        this.couponMapper = couponMapper;
    }

    @Autowired
    public void setGrantMapper(MallCouponGrantMapper grantMapper) {
        this.grantMapper = grantMapper;
    }

    @Autowired
    public void setGoodsMapper(MallGoodsMapper goodsMapper) {
        this.goodsMapper = goodsMapper;
    }

    @Autowired
    public void setSpecMapper(MallGoodsSpecMapper specMapper) {
        this.specMapper = specMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setTransactionTemplate(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 用卷后的报价：这张卷抵多少、实付多少。
     *
     * @param grant        发放记录（条款快照）
     * @param deductPoints 卷抵扣的积分
     * @param payPoints    实付积分，可为 0（兑换卷全额抵扣）
     */
    public record CouponQuote(MallCouponGrant grant, int deductPoints, int payPoints) {
    }

    // ================= 卷定义 =================

    /** 新建卷定义，默认启用。 */
    @Transactional(rollbackFor = Exception.class)
    public Long create(MallCouponSaveDTO dto, Long adminId) {
        requireAdmin(adminId);
        validateTerms(dto);
        MallCoupon c = new MallCoupon();
        c.setName(dto.getName().trim());
        c.setType(dto.getType());
        c.setGoodsId(dto.getGoodsId());
        c.setThresholdPoints(dto.getThresholdPoints());
        c.setDiscountPoints(dto.getDiscountPoints());
        c.setValidStart(dto.getValidStart());
        c.setValidEnd(dto.getValidEnd());
        c.setDescription(dto.getDescription());
        c.setStatus(COUPON_ENABLED);
        c.setCreateBy(adminId);
        couponMapper.insert(c);
        return c.getId();
    }

    /**
     * 修改卷定义。<b>只影响之后发出的卷</b>——已发出的卷在发放记录上有全套快照。
     *
     * <p>用 {@code LambdaUpdateWrapper} 显式 {@code .set()} 全部字段：MyBatis-Plus 的 {@code updateById}
     * 默认跳过 null，满减卷改成「全场通用」（goodsId 置空）会静默失效——勋章 update 被测试当场抓到过同一个坑。</p>
     */
    public void update(Long id, MallCouponSaveDTO dto) {
        validateTerms(dto);
        int rows = couponMapper.update(null, Wrappers.<MallCoupon>lambdaUpdate()
                .eq(MallCoupon::getId, id)
                .set(MallCoupon::getName, dto.getName().trim())
                .set(MallCoupon::getType, dto.getType())
                .set(MallCoupon::getGoodsId, dto.getGoodsId())
                .set(MallCoupon::getThresholdPoints, dto.getThresholdPoints())
                .set(MallCoupon::getDiscountPoints, dto.getDiscountPoints())
                .set(MallCoupon::getValidStart, dto.getValidStart())
                .set(MallCoupon::getValidEnd, dto.getValidEnd())
                .set(MallCoupon::getDescription, dto.getDescription())
                .set(MallCoupon::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("卷不存在");
        }
    }

    /** 启用 / 停用。停用只挡新发放，已发出的卷照常可用（同勋章「停用不影响已生效的发放」）。 */
    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != COUPON_ENABLED && status != COUPON_DISABLED)) {
            throw new BusinessException("状态只能是 1（启用）或 0（停用）");
        }
        int rows = couponMapper.update(null, Wrappers.<MallCoupon>lambdaUpdate()
                .eq(MallCoupon::getId, id)
                .set(MallCoupon::getStatus, status)
                .set(MallCoupon::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("卷不存在");
        }
    }

    /**
     * 字段之间的约束。<b>填了不该填的一律报错、不静默清空</b>——
     * 「兑换卷却填了抵扣积分」静默丢掉的话，管理员以为自己设了一张抵 20 的卷，实际发出去的是全额抵扣。
     * 与榜样「不跳转却填了链接报错而不是静默清空」同一条。
     */
    private void validateTerms(MallCouponSaveDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getName())) {
            throw new BusinessException("请填写卷名称");
        }
        if (!MallCouponType.isValid(dto.getType())) {
            throw new BusinessException("卷类型只能是 1（指定商品兑换卷）或 2（积分满减卷）");
        }
        if (dto.getValidStart() == null || dto.getValidEnd() == null) {
            throw new BusinessException("请填写有效期");
        }
        if (!dto.getValidEnd().isAfter(dto.getValidStart())) {
            throw new BusinessException("有效期止必须晚于有效期起");
        }
        if (dto.getType() == MallCouponType.EXCHANGE) {
            if (dto.getGoodsId() == null) {
                throw new BusinessException("指定商品兑换卷必须指定商品");
            }
            if (dto.getThresholdPoints() != null || dto.getDiscountPoints() != null) {
                throw new BusinessException("指定商品兑换卷是全额抵扣，不填满减门槛与抵扣积分");
            }
        } else {
            if (dto.getDiscountPoints() == null || dto.getDiscountPoints() <= 0) {
                throw new BusinessException("积分满减卷须填写大于 0 的抵扣积分");
            }
            // 门槛不低于抵扣：保证「实付 = 标价 - 抵扣」永远不为负，下单侧不必再写一个 max(0, ...)
            // 把一张「满 5 减 20」的卷悄悄变成全额抵扣。
            if (dto.getThresholdPoints() == null || dto.getThresholdPoints() < dto.getDiscountPoints()) {
                throw new BusinessException("满减门槛不得低于抵扣积分");
            }
        }
        if (dto.getGoodsId() != null && goodsMapper.selectById(dto.getGoodsId()) == null) {
            throw new BusinessException("适用商品不存在");
        }
    }

    // ================= 查询（管理端） =================

    /** 卷定义列表，带已发放 / 已使用张数（一次 group by，不逐卷查）。 */
    public PageResult<MallCouponVO> listForAdmin(PageQuery query, String keyword, Integer status) {
        IPage<MallCoupon> page = couponMapper.selectPage(query.toPage(),
                Wrappers.<MallCoupon>lambdaQuery()
                        .eq(status != null, MallCoupon::getStatus, status)
                        .like(StringUtils.hasText(keyword), MallCoupon::getName, keyword)
                        .orderByDesc(MallCoupon::getId));
        PageResult<MallCouponVO> result = PageResult.of(page.convert(this::toCouponVO));
        fillCouponExtras(result.getRecords());
        return result;
    }

    public MallCouponVO detailForAdmin(Long id) {
        MallCoupon c = id == null ? null : couponMapper.selectById(id);
        if (c == null) {
            throw new BusinessException("卷不存在");
        }
        MallCouponVO vo = toCouponVO(c);
        fillCouponExtras(List.of(vo));
        return vo;
    }

    /** 取卷名，给「该商品只能使用 X 兑换」这类文案用；查不到时给一个泛称而不是 null。 */
    public String nameOf(Long couponId) {
        MallCoupon c = couponId == null ? null : couponMapper.selectById(couponId);
        return c == null ? "指定卷" : c.getName();
    }

    /** 批量取卷名（商品列表带「需持卷兑换」用）。 */
    public Map<Long, String> namesOf(Collection<Long> couponIds) {
        if (couponIds == null || couponIds.isEmpty()) {
            return Map.of();
        }
        return couponMapper.selectList(Wrappers.<MallCoupon>lambdaQuery()
                        .select(MallCoupon::getId, MallCoupon::getName)
                        .in(MallCoupon::getId, couponIds))
                .stream().collect(Collectors.toMap(MallCoupon::getId, MallCoupon::getName, (a, b) -> a));
    }

    /** 商品设 require_coupon_id 前校验它指向一张存在的卷。 */
    public void requireExists(Long couponId) {
        if (couponId != null && couponMapper.selectById(couponId) == null) {
            throw new BusinessException("指定的卷不存在");
        }
    }

    private void fillCouponExtras(List<MallCouponVO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> ids = records.stream().map(MallCouponVO::getId).toList();
        Map<Long, long[]> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : grantMapper.selectMaps(Wrappers.<MallCouponGrant>query()
                .select("coupon_id AS couponId", "COUNT(*) AS cnt",
                        "SUM(CASE WHEN status = " + MallCouponGrantStatus.USED + " THEN 1 ELSE 0 END) AS usedCnt")
                .in("coupon_id", ids)
                .groupBy("coupon_id"))) {
            counts.put(((Number) row.get("couponId")).longValue(), new long[]{
                    ((Number) row.get("cnt")).longValue(),
                    row.get("usedCnt") == null ? 0 : ((Number) row.get("usedCnt")).longValue()});
        }
        Set<Long> goodsIds = records.stream().map(MallCouponVO::getGoodsId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> goodsNames = goodsIds.isEmpty() ? Map.of()
                : goodsMapper.selectList(Wrappers.<MallGoods>lambdaQuery()
                        .select(MallGoods::getId, MallGoods::getName)
                        .in(MallGoods::getId, goodsIds))
                .stream().collect(Collectors.toMap(MallGoods::getId, MallGoods::getName, (a, b) -> a));
        for (MallCouponVO vo : records) {
            long[] c = counts.get(vo.getId());
            vo.setGrantedCount(c == null ? 0L : c[0]);
            vo.setUsedCount(c == null ? 0L : c[1]);
            if (vo.getGoodsId() != null) {
                vo.setGoodsName(goodsNames.get(vo.getGoodsId()));
            }
        }
    }

    // ================= 发放 =================

    /**
     * 发卷 / 批量发卷。
     *
     * <p><b>幂等</b>：{@code uk_request_volunteer(request_id, volunteer_id)}。同一 requestId 的重放只会发一次；
     * 同一 requestId 若被用于<b>另一张卷</b>，按「载荷不同的撞键」报冲突——只判键存在会把键复用伪装成成功
     * （积分账本那条纪律）。</p>
     *
     * <p><b>按 requestId 上锁、锁在事务之外获取（项目既有约定）——这把锁是压测撞出来的，不是装饰</b>：
     * 同一批发放的并发重放（双击 / 弱网重试 / 网关重投）若不串行化，各自的「此前已发过哪些人」快照都读在
     * 第一个事务提交之前，于是每一行都去 INSERT、每一行都撞 {@code uk_request_volunteer}。
     * <b>撞键失败的 INSERT 会在 PRIMARY 的 supremum 上留下间隙锁</b>（CLAUDE.md 积分账本那一节按
     * {@code data_locks} 实测记过这个形态），而两个重放的下一次 INSERT 都要在同一个 supremum 间隙上取插入意向锁——
     * 互等成环，MySQL 判 {@code ER_LOCK_DEADLOCK}。{@code MallCouponStressTest} 的 8×400 重放风暴在没有这把锁时
     * 稳定复现，{@code MallCouponGrantConcurrencyTest} 把它钉成了必跑用例。串行化之后，后来者的快照一定读在
     * 前者提交之后，整批都落在「已发过」分支里、一行都不插。</p>
     *
     * <p><b>键是 requestId 而不是卷</b>：同一张卷的两次<b>不同</b>发放本来就不冲突（不同 requestId、不同唯一键），
     * 按卷上锁只会让它们白白排队。<b>唯一键仍是正确性的最后一道</b>——锁只把「撞键」从常态变成例外。</p>
     *
     * <p><b>插入按志愿者 id 升序</b>、<b>整批一个事务</b>：要么这批人全发到，要么（出现冲突时）一个都不发。</p>
     */
    public CouponGrantResultVO grant(Long couponId, MallCouponGrantDTO dto, Long adminId) {
        requireAdmin(adminId);
        if (dto == null || !StringUtils.hasText(dto.getRequestId())
                || !REQUEST_ID_PATTERN.matcher(dto.getRequestId()).matches()) {
            throw new BusinessException("幂等键只能由字母、数字与 : . _ - 组成，且不超过 64 位");
        }
        String requestId = dto.getRequestId();
        CouponGrantResultVO result = new CouponGrantResultVO();

        // 目标解析是只读的，放在锁与事务之外，不延长锁持有时间
        TreeSet<Long> targets = new TreeSet<>();
        if (dto.getVolunteerIds() != null) {
            dto.getVolunteerIds().stream().filter(Objects::nonNull).forEach(targets::add);
        }
        if (dto.getPhones() != null && !dto.getPhones().isEmpty()) {
            LinkedHashSet<String> phones = dto.getPhones().stream()
                    .filter(StringUtils::hasText).map(String::trim)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<String, Long> byPhone = volunteerQueryService.findIdsByPhones(phones);
            for (String phone : phones) {
                Long id = byPhone.get(phone);
                if (id == null) {
                    result.getUnmatchedPhones().add(phone);
                } else {
                    targets.add(id);
                }
            }
        }
        if (targets.isEmpty() && result.getUnmatchedPhones().isEmpty()) {
            throw new BusinessException("请至少指定一位发放对象");
        }
        if (targets.size() > MAX_GRANT_TARGETS) {
            throw new BusinessException("单次最多发放 " + MAX_GRANT_TARGETS + " 人，请分批");
        }
        result.setRequested(targets.size() + result.getUnmatchedPhones().size());
        if (targets.isEmpty()) {
            return result;
        }
        return DistributedLockSupport.runLocked(redissonClient, GRANT_LOCK_PREFIX + requestId,
                () -> transactionTemplate.execute(s -> doGrant(couponId, requestId, targets, adminId, result)));
    }

    private CouponGrantResultVO doGrant(Long couponId, String requestId, TreeSet<Long> targets, Long adminId,
                                        CouponGrantResultVO result) {
        LocalDateTime now = LocalDateTime.now();
        // 当前读 + S 锁：看得见别人刚提交的停用，且让并发的停用 / 修改排在本次发放之后（见 mapper 注释）
        MallCoupon coupon = couponMapper.selectByIdForShare(couponId);
        if (coupon == null) {
            throw new BusinessException("卷不存在");
        }
        if (coupon.getStatus() == null || coupon.getStatus() != COUPON_ENABLED) {
            throw new BusinessException("该卷已停用，不能再发放");
        }
        if (!coupon.getValidEnd().isAfter(now)) {
            throw new BusinessException("该卷已过有效期，不能再发放");
        }

        // 同一 requestId 此前已发过的：先整体查一次，载荷不符直接拒绝
        List<MallCouponGrant> prior = grantMapper.selectList(Wrappers.<MallCouponGrant>lambdaQuery()
                .eq(MallCouponGrant::getRequestId, requestId));
        Set<Long> already = new HashSet<>();
        for (MallCouponGrant g : prior) {
            if (!couponId.equals(g.getCouponId())) {
                throw new BusinessException("幂等键 requestId 已用于另一张卷的发放，请重新打开发卷窗口");
            }
            already.add(g.getVolunteerId());
        }

        Set<Long> eligible = volunteerQueryService.filterActiveRegistered(targets);
        for (Long volunteerId : targets) {
            if (already.contains(volunteerId)) {
                result.setAlreadyGranted(result.getAlreadyGranted() + 1);
                continue;
            }
            if (!eligible.contains(volunteerId)) {
                result.getIneligibleVolunteerIds().add(volunteerId);
                continue;
            }
            MallCouponGrant g = snapshotOf(coupon, volunteerId, requestId, adminId);
            try {
                grantMapper.insert(g);
                result.setGranted(result.getGranted() + 1);
            } catch (DuplicateKeyException e) {
                // 锁之外仍可能发生的并发（如另一个没走本方法的写入）：当前读取回冲突行复核载荷（见 mapper 注释）
                MallCouponGrant conflict = grantMapper.selectByRequestAndVolunteerForShare(requestId, volunteerId);
                if (conflict == null || !couponId.equals(conflict.getCouponId())) {
                    throw new BusinessException("幂等键 requestId 已用于另一张卷的发放，请重新打开发卷窗口");
                }
                result.setAlreadyGranted(result.getAlreadyGranted() + 1);
            }
        }
        return result;
    }

    /** 发放记录 = 卷定义条款的全套快照。 */
    private static MallCouponGrant snapshotOf(MallCoupon c, Long volunteerId, String requestId, Long adminId) {
        MallCouponGrant g = new MallCouponGrant();
        g.setCouponId(c.getId());
        g.setVolunteerId(volunteerId);
        g.setRequestId(requestId);
        g.setCouponName(c.getName());
        g.setType(c.getType());
        g.setGoodsId(c.getGoodsId());
        g.setThresholdPoints(c.getThresholdPoints());
        g.setDiscountPoints(c.getDiscountPoints());
        g.setValidStart(c.getValidStart());
        g.setExpireTime(c.getValidEnd());
        g.setStatus(MallCouponGrantStatus.UNUSED);
        g.setGrantBy(adminId);
        return g;
    }

    /**
     * 作废一张已发出的卷。<b>只有未使用的可作废</b>（CAS 判定）——
     * 已用在某张单上的卷作废了，那张单退回时就还不回去，账会对不上。
     */
    public void revoke(Long grantId, String reason, Long adminId) {
        requireAdmin(adminId);
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("请填写作废原因");
        }
        if (reason.length() > 512) {
            throw new BusinessException("作废原因不得超过 512 字");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = grantMapper.update(null, Wrappers.<MallCouponGrant>lambdaUpdate()
                .eq(MallCouponGrant::getId, grantId)
                .eq(MallCouponGrant::getStatus, MallCouponGrantStatus.UNUSED)
                .set(MallCouponGrant::getStatus, MallCouponGrantStatus.REVOKED)
                .set(MallCouponGrant::getRevokeBy, adminId)
                .set(MallCouponGrant::getRevokeTime, now)
                .set(MallCouponGrant::getRevokeReason, reason)
                .set(MallCouponGrant::getUpdateTime, now));
        if (rows == 1) {
            return;
        }
        MallCouponGrant g = grantId == null ? null : grantMapper.selectById(grantId);
        if (g == null) {
            throw new BusinessException("卷不存在");
        }
        throw new BusinessException(g.getStatus() != null && g.getStatus() == MallCouponGrantStatus.USED
                ? "这张卷已被使用，不能作废" : "这张卷已作废");
    }

    /** 某张卷的发放记录（管理端），status 支持派生的「3 已过期」。 */
    public PageResult<MallCouponGrantVO> listGrants(Long couponId, PageQuery query, Integer status) {
        LocalDateTime now = LocalDateTime.now();
        IPage<MallCouponGrant> page = grantMapper.selectPage(query.toPage(),
                applyStatusFilter(Wrappers.<MallCouponGrant>lambdaQuery()
                        .eq(MallCouponGrant::getCouponId, couponId), status, now)
                        .orderByDesc(MallCouponGrant::getId));
        PageResult<MallCouponGrantVO> result = PageResult.of(page.convert(g -> toGrantVO(g, true, now)));
        Map<Long, String> names = volunteerQueryService.listNamesByIds(result.getRecords().stream()
                .map(MallCouponGrantVO::getVolunteerId).collect(Collectors.toSet()));
        result.getRecords().forEach(vo -> vo.setVolunteerName(names.get(vo.getVolunteerId())));
        return result;
    }

    // ================= 志愿者端 =================

    /**
     * 我的卷（Row 8 C）。status：0 可用（未使用且未过期）/ 1 已使用 / 2 已作废 / 3 已过期 / 不传=全部。
     */
    public PageResult<MallCouponGrantVO> listMine(Long volunteerId, PageQuery query, Integer status) {
        LocalDateTime now = LocalDateTime.now();
        IPage<MallCouponGrant> page = grantMapper.selectPage(query.toPage(),
                applyStatusFilter(Wrappers.<MallCouponGrant>lambdaQuery()
                        .eq(MallCouponGrant::getVolunteerId, volunteerId), status, now)
                        .orderByDesc(MallCouponGrant::getId));
        return PageResult.of(page.convert(g -> toGrantVO(g, false, now)));
    }

    /**
     * 这件规格我手上<b>此刻能用</b>的卷（下单前选卷用），先到期的排前面。
     *
     * <p>适用判定与下单走同一个 {@link #inapplicableReason}——列表说能用，下单就不会报不适用。</p>
     */
    public List<MallCouponGrantVO> listUsableForSpec(Long volunteerId, Long specId) {
        MallGoodsSpec spec = specId == null ? null : specMapper.selectById(specId);
        if (spec == null) {
            throw new BusinessException("商品规格不存在");
        }
        MallGoods goods = goodsMapper.selectById(spec.getGoodsId());
        if (goods == null || goods.getStatus() == null || goods.getStatus() != MallGoodsStatus.ON_SALE
                || (goods.getHidden() != null && goods.getHidden() == 1)) {
            throw new BusinessException("商品不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        int price = spec.getPoints() == null ? 0 : spec.getPoints();
        List<MallCouponGrant> candidates = grantMapper.selectList(Wrappers.<MallCouponGrant>lambdaQuery()
                .eq(MallCouponGrant::getVolunteerId, volunteerId)
                .eq(MallCouponGrant::getStatus, MallCouponGrantStatus.UNUSED)
                .le(MallCouponGrant::getValidStart, now)
                .gt(MallCouponGrant::getExpireTime, now)
                .orderByAsc(MallCouponGrant::getExpireTime)
                .last("LIMIT 200"));
        List<MallCouponGrantVO> usable = new ArrayList<>();
        for (MallCouponGrant g : candidates) {
            if (inapplicableReason(g, goods, price) == null) {
                usable.add(toGrantVO(g, false, now));
            }
        }
        return usable;
    }

    // ================= 供下单使用 =================

    /**
     * 算出用这张卷之后的实付，<b>不适用时抛出准确原因</b>。只读，不改卷。
     *
     * <p>「此刻仍可用」这组会被并发改变的条件，由下单侧随后的 {@link #use} CAS 再判一次——
     * 这里的预检只为给出说得清的文案，不是正确性的来源。</p>
     *
     * @param now 与随后 {@link #use} 用同一个时间，保证预检与 CAS 对「过没过期」的判断一致
     */
    public CouponQuote quote(Long volunteerId, Long grantId, MallGoods goods, int price, LocalDateTime now) {
        MallCouponGrant g = grantId == null ? null : grantMapper.selectById(grantId);
        // 不是本人的与不存在返回同一句话，防按 id 枚举别人的卷
        if (g == null || !g.getVolunteerId().equals(volunteerId)) {
            throw new BusinessException("卷不存在");
        }
        int status = g.getStatus() == null ? MallCouponGrantStatus.UNUSED : g.getStatus();
        if (status == MallCouponGrantStatus.USED) {
            throw new BusinessException("这张卷已被使用");
        }
        if (status == MallCouponGrantStatus.REVOKED) {
            throw new BusinessException("这张卷已作废");
        }
        if (g.getValidStart() != null && g.getValidStart().isAfter(now)) {
            throw new BusinessException("这张卷尚未生效，" + MSG_TIME.format(g.getValidStart()) + " 起可用");
        }
        if (!g.getExpireTime().isAfter(now)) {
            throw new BusinessException("这张卷已过期");
        }
        String why = inapplicableReason(g, goods, price);
        if (why != null) {
            throw new BusinessException(why);
        }
        int deduct = g.getType() == MallCouponType.EXCHANGE ? price : Math.min(g.getDiscountPoints(), price);
        return new CouponQuote(g, deduct, price - deduct);
    }

    /**
     * 这张卷能不能用在这件商品（这个价）上。能用返回 null，不能用返回给人看的原因。
     *
     * <p><b>全系统只在这里判</b>，下单与「可用的卷」列表共用。</p>
     */
    static String inapplicableReason(MallCouponGrant g, MallGoods goods, int price) {
        if (goods == null) {
            return "商品不存在";
        }
        if (goods.getRequireCouponId() != null && !goods.getRequireCouponId().equals(g.getCouponId())) {
            return "该商品只能使用指定的卷兑换";
        }
        if (!MallCouponType.isValid(g.getType())) {
            return "卷类型无效";
        }
        if (g.getType() == MallCouponType.EXCHANGE) {
            return goods.getId().equals(g.getGoodsId()) ? null : "这张兑换卷不适用于该商品";
        }
        if (g.getGoodsId() != null && !g.getGoodsId().equals(goods.getId())) {
            return "这张满减卷不适用于该商品";
        }
        if (g.getDiscountPoints() == null || g.getDiscountPoints() <= 0) {
            return "卷的抵扣积分无效";
        }
        if (g.getThresholdPoints() != null && price < g.getThresholdPoints()) {
            return "未达到满减门槛（满 " + g.getThresholdPoints() + " 积分可用）";
        }
        return null;
    }

    /**
     * 把卷用在这张单上（CAS）。返回 false = 预检之后被并发用掉 / 作废 / 恰好到期，调用方应整单回滚。
     */
    public boolean use(Long grantId, Long volunteerId, Long orderId, LocalDateTime now) {
        return grantMapper.useGrant(grantId, volunteerId, orderId, now,
                MallCouponGrantStatus.UNUSED, MallCouponGrantStatus.USED) == 1;
    }

    /**
     * 退单时归还卷。<b>不抛异常</b>——与还库存那条同一个取舍：抛了会连退分一起回滚，
     * 志愿者会因为一张卷的数据问题而永远取消不了单。代价是只留一行日志，所以日志要说清是哪张单哪张卷。
     */
    public void restore(Long grantId, Long orderId) {
        int rows = grantMapper.restoreGrant(grantId, orderId, LocalDateTime.now(),
                MallCouponGrantStatus.UNUSED, MallCouponGrantStatus.USED);
        if (rows != 1) {
            log.warn("归还卷失败：兑换单 {} 的卷 {} 不在「用在本单上」的状态，订单已退回但卷未归还，请核对数据",
                    orderId, grantId);
        }
    }

    // ================= 内部 =================

    /** 把 status 筛选落成查询条件；3（已过期）是派生态，落成「未使用 且 expire_time <= now」。 */
    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MallCouponGrant>
    applyStatusFilter(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MallCouponGrant> w,
                      Integer status, LocalDateTime now) {
        if (status == null) {
            return w;
        }
        switch (status) {
            case MallCouponGrantStatus.UNUSED -> w.eq(MallCouponGrant::getStatus, MallCouponGrantStatus.UNUSED)
                    .gt(MallCouponGrant::getExpireTime, now);
            case MallCouponGrantStatus.EXPIRED -> w.eq(MallCouponGrant::getStatus, MallCouponGrantStatus.UNUSED)
                    .le(MallCouponGrant::getExpireTime, now);
            case MallCouponGrantStatus.USED, MallCouponGrantStatus.REVOKED -> w.eq(MallCouponGrant::getStatus, status);
            default -> throw new BusinessException("状态筛选只能是 0 可用 / 1 已使用 / 2 已作废 / 3 已过期");
        }
        return w;
    }

    private MallCouponVO toCouponVO(MallCoupon c) {
        MallCouponVO vo = new MallCouponVO();
        vo.setId(c.getId());
        vo.setName(c.getName());
        vo.setType(c.getType());
        vo.setTypeLabel(MallCouponType.labelOf(c.getType()));
        vo.setGoodsId(c.getGoodsId());
        vo.setThresholdPoints(c.getThresholdPoints());
        vo.setDiscountPoints(c.getDiscountPoints());
        vo.setValidStart(c.getValidStart());
        vo.setValidEnd(c.getValidEnd());
        vo.setStatus(c.getStatus());
        vo.setDescription(c.getDescription());
        vo.setCreateTime(c.getCreateTime());
        return vo;
    }

    private static MallCouponGrantVO toGrantVO(MallCouponGrant g, boolean forAdmin, LocalDateTime now) {
        MallCouponGrantVO vo = new MallCouponGrantVO();
        vo.setId(g.getId());
        vo.setCouponId(g.getCouponId());
        vo.setCouponName(g.getCouponName());
        vo.setType(g.getType());
        vo.setTypeLabel(MallCouponType.labelOf(g.getType()));
        vo.setGoodsId(g.getGoodsId());
        vo.setThresholdPoints(g.getThresholdPoints());
        vo.setDiscountPoints(g.getDiscountPoints());
        vo.setValidStart(g.getValidStart());
        vo.setExpireTime(g.getExpireTime());
        int display = MallCouponGrantStatus.displayStatus(g.getStatus(), g.getValidStart(), g.getExpireTime(), now);
        vo.setStatus(display);
        vo.setStatusLabel(MallCouponGrantStatus.labelOf(display));
        vo.setUsedOrderId(g.getUsedOrderId());
        vo.setUsedTime(g.getUsedTime());
        vo.setCreateTime(g.getCreateTime());
        if (forAdmin) {
            vo.setVolunteerId(g.getVolunteerId());
            vo.setRevokeReason(g.getRevokeReason());
            vo.setRevokeTime(g.getRevokeTime());
        }
        return vo;
    }

    private static void requireAdmin(Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
    }
}
