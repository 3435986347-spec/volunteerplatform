package com.hengde.enterprise.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.config.StpEnterpriseUtil;
import com.hengde.auth.service.AdminQueryService;
import com.hengde.common.crypto.CryptoUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.oss.FileStorageService;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.utils.PasswordUtil;
import com.hengde.donate.service.MallGoodsService;
import com.hengde.enterprise.constant.EnterpriseStatus;
import com.hengde.enterprise.dao.EnterpriseAccountMapper;
import com.hengde.enterprise.dto.EnterpriseDTOs;
import com.hengde.enterprise.entity.EnterpriseAccount;
import com.hengde.enterprise.vo.EnterpriseVOs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 后台爱心企业管理（V4 爱心企业批，Row 15 F「查看、管理、搜索、注册审核、批量导出，删除，暂停；后台注册企业账号」）。
 *
 * <p><b>状态迁移全是 CAS</b>（条件写在 UPDATE 的 WHERE 里）：通过 / 驳回只对待审核的，暂停只对正常的，恢复只对暂停的；
 * 输家的报错在 CAS 失败之后<b>重新读一次</b>再说「当前：xx」（autocommit 下每条语句各自一份快照，读到的就是赢家提交后的状态）。
 * 暂停与删除之后立刻踢掉这家企业的全部登录；已经在飞的请求由 api 的企业端闸门在下一次请求时拦下。</p>
 *
 * <p><b>暂停 / 删除 / 恢复与「它赞助的商品是否可用」同一事务</b>（{@link MallGoodsService#setSponsorSuspended}）：
 * 企业暂停了，它的商品对志愿者不可见、下单那条扣库存语句也不放行；恢复即复原。两件事分开提交，中间崩一次就会留下
 * 「企业已暂停、商品照样能换」或反过来。</p>
 *
 * @author hengde
 */
@Service
public class EnterpriseAdminService {

    static final int EXPORT_MAX = 20000;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private EnterpriseAccountMapper accountMapper;
    private CryptoUtil cryptoUtil;
    private FileStorageService fileStorageService;
    private AdminQueryService adminQueryService;
    private MallGoodsService mallGoodsService;
    private TransactionTemplate transactionTemplate;

    @Autowired
    public void setMallGoodsService(MallGoodsService mallGoodsService) {
        this.mallGoodsService = mallGoodsService;
    }

    @Autowired
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Autowired
    public void setAccountMapper(EnterpriseAccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    @Autowired
    public void setCryptoUtil(CryptoUtil cryptoUtil) {
        this.cryptoUtil = cryptoUtil;
    }

    @Autowired
    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Autowired
    public void setAdminQueryService(AdminQueryService adminQueryService) {
        this.adminQueryService = adminQueryService;
    }

    /** @param status 0待审核/1正常/2已驳回/3已暂停；空＝全部。keyword：企业名称 / 信用代码 / 登录账号片段，或负责人完整手机号 */
    public PageResult<EnterpriseVOs.Account> list(PageQuery query, Integer status, String keyword) {
        IPage<EnterpriseAccount> page = accountMapper.selectPage(query.toPage(), filter(status, keyword));
        return PageResult.of(toVOs(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public EnterpriseVOs.Account detail(Long id) {
        return toVOs(List.of(require(id))).get(0);
    }

    public static List<String> exportHead() {
        return List.of("企业编号", "企业名称", "统一社会信用代码", "状态", "来源", "项目负责人", "负责人手机号", "对外电话", "地址",
                "登录账号", "提交时间", "审核人", "审核时间", "驳回原因", "暂停原因", "最近登录");
    }

    public List<List<String>> exportRows(Integer status, String keyword) {
        List<EnterpriseAccount> rows = accountMapper.selectList(filter(status, keyword).last("LIMIT " + (EXPORT_MAX + 1)));
        if (rows.size() > EXPORT_MAX) {
            throw new BusinessException("一次最多导出 " + EXPORT_MAX + " 条，请加筛选条件");
        }
        List<List<String>> out = new ArrayList<>();
        for (EnterpriseVOs.Account a : toVOs(rows)) {
            out.add(List.of(String.valueOf(a.getId()), nz(a.getName()), nz(a.getCreditCode()), a.getStatusLabel(),
                    Objects.equals(a.getSource(), EnterpriseStatus.SOURCE_ADMIN) ? "后台代建" : "自助注册",
                    nz(a.getLeaderName()), nz(a.getLeaderPhone()), nz(a.getContactPhone()), nz(a.getAddress()), nz(a.getUsername()),
                    fmt(a.getSubmitTime()), nz(a.getAuditByName()), fmt(a.getAuditTime()), nz(a.getRejectReason()),
                    nz(a.getPauseReason()), fmt(a.getLastLoginTime())));
        }
        return out;
    }

    /** 后台注册企业账号：直接为正常状态（视同审核通过，审核人记本人）。 */
    public Long create(EnterpriseDTOs.AdminCreate dto, Long adminId) {
        requireOperator(adminId);
        EnterpriseAccount a = new EnterpriseAccount();
        a.setName(EnterpriseSupport.requireText(dto.getName(), "请填写企业名称"));
        a.setCreditCode(EnterpriseSupport.normalizeCreditCode(dto.getCreditCode()));
        a.setLogoUrl(EnterpriseSupport.trimToNull(dto.getLogoUrl()));
        EnterpriseSupport.requireOwnImage(fileStorageService, a.getLogoUrl());
        a.setIntro(EnterpriseSupport.trimToNull(dto.getIntro()));
        a.setAddress(EnterpriseSupport.trimToNull(dto.getAddress()));
        a.setContactPhone(EnterpriseSupport.trimToNull(dto.getContactPhone()));
        a.setLeaderName(EnterpriseSupport.requireText(dto.getLeaderName(), "请填写项目负责人"));
        a.setLeaderPhone(cryptoUtil.encrypt(dto.getLeaderPhone()));
        a.setLeaderPhoneHash(cryptoUtil.hashPhone(dto.getLeaderPhone()));
        a.setUsername(EnterpriseSupport.requireText(dto.getUsername(), "请填写登录账号"));
        a.setPassword(PasswordUtil.encrypt(dto.getPassword()));
        LocalDateTime now = LocalDateTime.now().withNano(0);
        a.setStatus(EnterpriseStatus.NORMAL);
        a.setSource(EnterpriseStatus.SOURCE_ADMIN);
        a.setSubmitTime(now);
        a.setAuditBy(adminId);
        a.setAuditTime(now);
        a.setCreatedBy(adminId);
        try {
            accountMapper.insert(a);
        } catch (DuplicateKeyException e) {
            throw EnterpriseSupport.duplicate(e);
        }
        return a.getId();
    }

    public void approve(Long id, Long adminId) {
        requireOperator(adminId);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        int rows = accountMapper.update(null, Wrappers.<EnterpriseAccount>lambdaUpdate()
                .eq(EnterpriseAccount::getId, id)
                .eq(EnterpriseAccount::getStatus, EnterpriseStatus.PENDING)
                .set(EnterpriseAccount::getStatus, EnterpriseStatus.NORMAL)
                .set(EnterpriseAccount::getRejectReason, null)
                .set(EnterpriseAccount::getAuditBy, adminId)
                .set(EnterpriseAccount::getAuditTime, now)
                .set(EnterpriseAccount::getUpdateTime, now));
        requireMoved(rows, id, "只有待审核的入驻申请可以通过");
    }

    public void reject(Long id, String reason, Long adminId) {
        requireOperator(adminId);
        String r = reason(reason);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        int rows = accountMapper.update(null, Wrappers.<EnterpriseAccount>lambdaUpdate()
                .eq(EnterpriseAccount::getId, id)
                .eq(EnterpriseAccount::getStatus, EnterpriseStatus.PENDING)
                .set(EnterpriseAccount::getStatus, EnterpriseStatus.REJECTED)
                .set(EnterpriseAccount::getRejectReason, r)
                .set(EnterpriseAccount::getAuditBy, adminId)
                .set(EnterpriseAccount::getAuditTime, now)
                .set(EnterpriseAccount::getUpdateTime, now));
        requireMoved(rows, id, "只有待审核的入驻申请可以驳回");
    }

    public void pause(Long id, String reason, Long adminId) {
        requireOperator(adminId);
        String r = reason(reason);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Integer rows = transactionTemplate.execute(tx -> {
            int n = accountMapper.update(null, Wrappers.<EnterpriseAccount>lambdaUpdate()
                    .eq(EnterpriseAccount::getId, id)
                    .eq(EnterpriseAccount::getStatus, EnterpriseStatus.NORMAL)
                    .set(EnterpriseAccount::getStatus, EnterpriseStatus.PAUSED)
                    .set(EnterpriseAccount::getPauseReason, r)
                    .set(EnterpriseAccount::getPausedBy, adminId)
                    .set(EnterpriseAccount::getPausedTime, now)
                    .set(EnterpriseAccount::getUpdateTime, now));
            if (n == 1) {
                mallGoodsService.setSponsorSuspended(id, true);
            }
            return n;
        });
        requireMoved(rows == null ? 0 : rows, id, "只有正常的企业可以暂停");
        StpEnterpriseUtil.logout(id);
    }

    public void resume(Long id, Long adminId) {
        requireOperator(adminId);
        Integer rows = transactionTemplate.execute(tx -> {
            int n = accountMapper.update(null, Wrappers.<EnterpriseAccount>lambdaUpdate()
                    .eq(EnterpriseAccount::getId, id)
                    .eq(EnterpriseAccount::getStatus, EnterpriseStatus.PAUSED)
                    .set(EnterpriseAccount::getStatus, EnterpriseStatus.NORMAL)
                    .set(EnterpriseAccount::getUpdateTime, LocalDateTime.now()));
            if (n == 1) {
                mallGoodsService.setSponsorSuspended(id, false);
            }
            return n;
        });
        requireMoved(rows == null ? 0 : rows, id, "只有已暂停的企业可以恢复");
    }

    /** 删除（逻辑删除，释放登录账号与信用代码）。 */
    public void delete(Long id, Long adminId) {
        requireOperator(adminId);
        Boolean deleted = transactionTemplate.execute(tx -> {
            if (accountMapper.deleteById(id) != 1) {
                return false;
            }
            mallGoodsService.setSponsorSuspended(id, true);
            return true;
        });
        if (!Boolean.TRUE.equals(deleted)) {
            throw new BusinessException("企业不存在或已删除");
        }
        StpEnterpriseUtil.logout(id);
    }

    // ================= 内部 =================

    private LambdaQueryWrapper<EnterpriseAccount> filter(Integer status, String keyword) {
        LambdaQueryWrapper<EnterpriseAccount> w = Wrappers.<EnterpriseAccount>lambdaQuery()
                .eq(status != null, EnterpriseAccount::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            String k = keyword.trim();
            if (k.matches("^1[3-9]\\d{9}$")) {
                w.eq(EnterpriseAccount::getLeaderPhoneHash, cryptoUtil.hashPhone(k));
            } else {
                w.and(x -> x.like(EnterpriseAccount::getName, k).or().like(EnterpriseAccount::getCreditCode, k)
                        .or().like(EnterpriseAccount::getUsername, k));
            }
        }
        // 待审核先交的在前，其余新的在前
        return w.orderBy(true, Objects.equals(status, EnterpriseStatus.PENDING), EnterpriseAccount::getId);
    }

    private List<EnterpriseVOs.Account> toVOs(List<EnterpriseAccount> rows) {
        List<Long> admins = rows.stream().map(EnterpriseAccount::getAuditBy).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, String> names = admins.isEmpty() ? Map.of() : adminQueryService.listNamesByIds(admins);
        List<EnterpriseVOs.Account> out = new ArrayList<>(rows.size());
        for (EnterpriseAccount a : rows) {
            out.add(EnterpriseSupport.toAccount(a, cryptoUtil.decrypt(a.getLeaderPhone()),
                    a.getAuditBy() == null ? null : names.get(a.getAuditBy())));
        }
        return out;
    }

    private void requireMoved(int rows, Long id, String message) {
        if (rows != 1) {
            EnterpriseAccount now = accountMapper.selectById(id);
            throw new BusinessException(now == null ? "企业不存在或已删除" : message + "（当前：" + EnterpriseStatus.label(now.getStatus()) + "）");
        }
    }

    private EnterpriseAccount require(Long id) {
        EnterpriseAccount a = id == null ? null : accountMapper.selectById(id);
        if (a == null) {
            throw new BusinessException("企业不存在或已删除");
        }
        return a;
    }

    private static void requireOperator(Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
    }

    private static String reason(String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("请填写原因");
        }
        String r = reason.trim();
        if (r.length() > 255) {
            throw new BusinessException("原因不超过 255 字");
        }
        return r;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "" : t.format(FMT);
    }
}
