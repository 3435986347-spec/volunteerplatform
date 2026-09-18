package com.hengde.enterprise.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.lock.DistributedLockSupport;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.service.MallOrderService;
import com.hengde.donate.vo.SponsorPickedOrderView;
import com.hengde.enterprise.dao.EnterprisePointRecordMapper;
import com.hengde.enterprise.dto.EnterprisePointDTOs;
import com.hengde.enterprise.entity.EnterpriseAccount;
import com.hengde.enterprise.entity.EnterprisePointRecord;
import com.hengde.enterprise.vo.EnterprisePointVOs;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 爱心企业积分账本（V4 爱心企业批，Row 15 F「积分流转」，V4规划 D5）：独立账本、余额＝流水之和。
 *
 * <p><b>兑换入账</b>：赞助商品的兑换单变成「已领取」之后补记（{@link #creditPickedOrders}，由定时任务与后台显式补记触发）。
 * 不挂在核销那条语句上：领取有三条路（现场核销 / 本人确认收货 / 系统自动确认，最后一条是一条批量 UPDATE），
 * 挂事件会漏掉批量那条，而 donate 不能反过来依赖 enterprise。幂等靠 {@code uk_source(1, 兑换单 id)}：
 * 几个补记同时跑、重复跑，每张单只记一次。金额＝实际扣分减去抵扣快递费的部分；0 分不记。</p>
 *
 * <p><b>后台调整</b>（兑换企业权益时扣减，或更正）：幂等键 {@code request_id}，<b>命中已有的那一笔必须复核载荷</b>
 * （企业 / 数额 / 说明 / 操作人），一致才算重放、不一致报冲突——只判键存在会把键复用伪装成成功（同志愿者账本那一课）。
 * 扣减不许把余额扣成负数，按企业上 Redisson 锁（{@code lock:enterprise:points:}，锁在事务外）串行：入账只会让余额变大，不需要进这把锁。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
public class EnterprisePointService {

    static final String LOCK_PREFIX = "lock:enterprise:points:";
    static final int PAGE = 500;

    private EnterprisePointRecordMapper recordMapper;
    private MallOrderService mallOrderService;
    private EnterpriseQueryService enterpriseQueryService;
    private RedissonClient redissonClient;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setRecordMapper(EnterprisePointRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    @Autowired
    public void setMallOrderService(MallOrderService mallOrderService) {
        this.mallOrderService = mallOrderService;
    }

    @Autowired
    public void setEnterpriseQueryService(EnterpriseQueryService enterpriseQueryService) {
        this.enterpriseQueryService = enterpriseQueryService;
    }

    @Autowired
    public void setRedissonClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 补记 {@code since} 起领取的赞助商品兑换单。
     *
     * @return 本次新记账的条数
     */
    public int creditPickedOrders(LocalDateTime since) {
        if (since == null) {
            throw new BusinessException("请给出补记的起始时间");
        }
        int credited = 0;
        long after = 0;
        while (true) {
            List<SponsorPickedOrderView> page = mallOrderService.pickedSponsorOrders(since, after, PAGE);
            if (page.isEmpty()) {
                break;
            }
            after = page.get(page.size() - 1).getOrderId();
            Set<Long> done = new HashSet<>(recordMapper.selectCreditedOrderIds(page.stream().map(SponsorPickedOrderView::getOrderId).toList()));
            for (SponsorPickedOrderView v : page) {
                if (done.contains(v.getOrderId()) || v.getGoodsPoints() == null || v.getGoodsPoints() <= 0) {
                    continue;
                }
                EnterprisePointRecord r = new EnterprisePointRecord();
                r.setEnterpriseId(v.getEnterpriseId());
                r.setChangeAmount(v.getGoodsPoints());
                r.setSourceType(EnterprisePointRecord.SOURCE_EXCHANGE);
                r.setSourceId(v.getOrderId());
                r.setRemark(cut("兑换入账：" + v.getGoodsName() + "（" + v.getOrderNo() + "）", 255));
                r.setCreateTime(LocalDateTime.now().withNano(0));
                try {
                    recordMapper.insert(r);
                    credited++;
                } catch (DuplicateKeyException e) {
                    // 并发的另一次补记刚记上：正是 uk_source 要挡的
                }
            }
            if (page.size() < PAGE) {
                break;
            }
        }
        return credited;
    }

    public EnterprisePointVOs.Summary summary(Long enterpriseId) {
        EnterprisePointVOs.Summary vo = new EnterprisePointVOs.Summary();
        vo.setEnterpriseId(enterpriseId);
        long exchanged = 0;
        long adjusted = 0;
        for (Map<String, Object> row : recordMapper.selectMaps(new QueryWrapper<EnterprisePointRecord>()
                .select("source_type AS st", "COALESCE(SUM(change_amount), 0) AS total")
                .eq("enterprise_id", enterpriseId)
                .groupBy("source_type"))) {
            long total = ((Number) row.get("total")).longValue();
            if (Objects.equals(((Number) row.get("st")).intValue(), EnterprisePointRecord.SOURCE_EXCHANGE)) {
                exchanged = total;
            } else {
                adjusted += total;
            }
        }
        vo.setTotalExchanged(exchanged);
        vo.setTotalAdjusted(adjusted);
        vo.setBalance(exchanged + adjusted);
        return vo;
    }

    /** @param sourceType 1兑换入账/2后台调整；空＝全部。新的在前 */
    public PageResult<EnterprisePointVOs.Record> records(Long enterpriseId, PageQuery query, Integer sourceType) {
        IPage<EnterprisePointRecord> page = recordMapper.selectPage(query.toPage(), Wrappers.<EnterprisePointRecord>lambdaQuery()
                .eq(EnterprisePointRecord::getEnterpriseId, enterpriseId)
                .eq(sourceType != null, EnterprisePointRecord::getSourceType, sourceType)
                .orderByDesc(EnterprisePointRecord::getCreateTime)
                .orderByDesc(EnterprisePointRecord::getId));
        return PageResult.of(page.convert(r -> {
            EnterprisePointVOs.Record vo = new EnterprisePointVOs.Record();
            vo.setId(r.getId());
            vo.setChangeAmount(r.getChangeAmount());
            vo.setSourceType(r.getSourceType());
            vo.setSourceLabel(Objects.equals(r.getSourceType(), EnterprisePointRecord.SOURCE_EXCHANGE) ? "兑换入账" : "后台调整");
            vo.setSourceId(r.getSourceId());
            vo.setRemark(r.getRemark());
            vo.setCreateTime(r.getCreateTime());
            return vo;
        }));
    }

    /** 后台调整。重复提交同一个幂等键只记一次（载荷必须一致）。 */
    public EnterprisePointVOs.Summary adjust(Long enterpriseId, EnterprisePointDTOs.Adjust dto, Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
        if (dto == null || dto.getAmount() == null || dto.getAmount() == 0) {
            throw new BusinessException("调整数额不能为 0");
        }
        if (!StringUtils.hasText(dto.getRemark()) || !StringUtils.hasText(dto.getRequestId())
                || !dto.getRequestId().matches("^[A-Za-z0-9:._-]{8,64}$")) {
            throw new BusinessException("请填写说明与合法的幂等键");
        }
        EnterpriseAccount account = enterpriseQueryService.find(enterpriseId);
        if (account == null) {
            throw new BusinessException("企业不存在或已删除");
        }
        String remark = cut(dto.getRemark().trim(), 255);
        DistributedLockSupport.runLocked(redissonClient, LOCK_PREFIX + enterpriseId, () -> transactionTemplate.execute(tx -> {
            EnterprisePointRecord existing = recordMapper.selectOne(Wrappers.<EnterprisePointRecord>lambdaQuery()
                    .eq(EnterprisePointRecord::getRequestId, dto.getRequestId()));
            if (existing != null) {
                requireSamePayload(existing, enterpriseId, dto.getAmount(), remark, adminId);
                return null;
            }
            if (dto.getAmount() < 0) {
                long balance = recordMapper.sumBalance(enterpriseId);
                if (balance + dto.getAmount() < 0) {
                    throw new BusinessException("企业积分余额不足（当前 " + balance + "）");
                }
            }
            EnterprisePointRecord r = new EnterprisePointRecord();
            r.setEnterpriseId(enterpriseId);
            r.setChangeAmount(dto.getAmount());
            r.setSourceType(EnterprisePointRecord.SOURCE_ADJUST);
            r.setRequestId(dto.getRequestId());
            r.setRemark(remark);
            r.setOperatorId(adminId);
            r.setCreateTime(LocalDateTime.now().withNano(0));
            try {
                recordMapper.insert(r);
            } catch (DuplicateKeyException e) {
                requireSamePayload(recordMapper.selectByRequestIdForShare(dto.getRequestId()), enterpriseId, dto.getAmount(), remark, adminId);
            }
            return null;
        }));
        return summary(enterpriseId);
    }

    private static void requireSamePayload(EnterprisePointRecord r, Long enterpriseId, int amount, String remark, Long adminId) {
        if (r == null || !Objects.equals(r.getEnterpriseId(), enterpriseId) || r.getChangeAmount() != amount
                || !Objects.equals(r.getRemark(), remark) || !Objects.equals(r.getOperatorId(), adminId)) {
            throw new BusinessException("幂等键已用于另一笔调整，请刷新后重试");
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max));
    }
}
