package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.dao.MallGoodsMapper;
import com.hengde.donate.entity.MallGoods;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 积分商品管理与审核（Row 8 F「积分商品管理板块：审核、兑换」）。
 *
 * @author hengde
 */
@Service
public class MallGoodsService {

    private MallGoodsMapper goodsMapper;

    @Autowired
    public void setGoodsMapper(MallGoodsMapper goodsMapper) {
        this.goodsMapper = goodsMapper;
    }

    /**
     * 修改商品。<b>改已过审的商品会把它退回待审核。</b>
     *
     * <p><b>为什么退回重审</b>：样式与价格是审核过的东西，改完还算「审核通过」等于给了一条
     * 绕过审核的路——先提交一版素净的过审，通过后再改成别的。商城这里更实：改的是
     * <b>价格、所需积分、赞助方</b>。同 {@code MedalService.update} 的判断。</p>
     *
     * <p><b>「不许改审核中的商品」这条限制写进 UPDATE 的 WHERE，靠影响行数判定</b>，
     * 不能只在方法开头 if 一下：那个 if 读的是 RR 快照，挡不住「修改先完成、审核后完成」
     * 这个交错——审核方把它改成已上架并提交时，本事务的 if 早已通过，
     * 于是一份没过审的内容被盖到了「已上架」上。</p>
     *
     * <p><b>纯展示字段不走这里</b>：排序与隐藏见 {@link #updateDisplay}，它们不影响审核结论，
     * 走同一个入口会让「临时隐藏一下」变成「重新排一次队」。</p>
     *
     * @param id     商品 id
     * @param values 新值（name/coverUrl/detail/sponsorName）
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, MallGoods values) {
        if (id == null) {
            throw new BusinessException("商品不存在");
        }
        if (!StringUtils.hasText(values.getName())) {
            throw new BusinessException("请填写商品名称");
        }
        int rows = goodsMapper.update(null, Wrappers.<MallGoods>lambdaUpdate()
                .eq(MallGoods::getId, id)
                // CAS：把「不许改审核中的商品」变成语句级的原子条件
                .ne(MallGoods::getStatus, MallGoodsStatus.PENDING)
                .set(MallGoods::getName, values.getName())
                .set(MallGoods::getCoverUrl, values.getCoverUrl())
                .set(MallGoods::getDetail, values.getDetail())
                .set(MallGoods::getSponsorName, values.getSponsorName())
                // 已上架 / 已停用 → 退回待审核；草稿与驳回稿保持原状（它们本就没过审）
                .setSql("status = CASE WHEN status IN (" + MallGoodsStatus.ON_SALE + ", "
                        + MallGoodsStatus.DISABLED + ") THEN " + MallGoodsStatus.PENDING + " ELSE status END")
                // 退回重审时把上一次的审核痕迹清掉，否则驳回原因会挂着误导审核人
                .set(MallGoods::getReviewBy, null)
                .set(MallGoods::getReviewTime, null)
                .set(MallGoods::getRejectReason, null)
                .set(MallGoods::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("商品不存在或正在审核中，无法修改");
        }
    }

    /**
     * 改排序 / 隐藏。<b>只发这两列，不触发重审。</b>
     *
     * <p>⚠️ <b>不要写成「读实体 → 改字段 → updateById」</b>：那样会把整行写回去，
     * 与并发的「改内容 → 退回待审核」互相覆盖——后提交的一方会把 status 盖回已上架，
     * 等于绕过审核。同 {@code MedalService.updateSort} 记过的那一课。</p>
     *
     * @param id     商品 id
     * @param sort   排序，null 表示不改
     * @param hidden 隐藏 0/1，null 表示不改
     */
    public void updateDisplay(Long id, Integer sort, Integer hidden) {
        if (hidden != null && hidden != 0 && hidden != 1) {
            throw new BusinessException("隐藏标记只能是 0 或 1");
        }
        if (sort == null && hidden == null) {
            return;
        }
        int rows = goodsMapper.update(null, Wrappers.<MallGoods>lambdaUpdate()
                .eq(MallGoods::getId, id)
                .set(sort != null, MallGoods::getSort, sort)
                .set(hidden != null, MallGoods::getHidden, hidden)
                .set(MallGoods::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("商品不存在");
        }
    }

    /** 提交审核：仅草稿与驳回稿可提交，CAS 判定。 */
    public void submit(Long id) {
        int rows = goodsMapper.update(null, Wrappers.<MallGoods>lambdaUpdate()
                .eq(MallGoods::getId, id)
                .in(MallGoods::getStatus, MallGoodsStatus.DRAFT, MallGoodsStatus.REJECTED)
                .set(MallGoods::getStatus, MallGoodsStatus.PENDING)
                .set(MallGoods::getSubmitTime, LocalDateTime.now())
                .set(MallGoods::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("商品不存在或当前状态不可提交审核");
        }
    }

    /**
     * 审核通过 → 已上架。
     *
     * <p>取商品用<b>当前读</b>（{@code FOR SHARE}）：RR 下事务里第一条 SELECT 就把读视图定死，
     * 普通查询看不见别人刚提交的改动。状态迁移本身仍由下面的 CAS 兜底，两者不是二选一——
     * 当前读让「这一刻它确实待审核」这句话成立，CAS 让迁移是原子的。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id, Long adminId) {
        requireAdmin(adminId);
        requirePending(id);
        casReview(id, MallGoodsStatus.ON_SALE, null, adminId);
    }

    /** 审核驳回 → 已驳回，记原因。 */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reason, Long adminId) {
        requireAdmin(adminId);
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("请填写驳回原因");
        }
        if (reason.length() > 512) {
            throw new BusinessException("驳回原因不得超过 512 字");
        }
        requirePending(id);
        casReview(id, MallGoodsStatus.REJECTED, reason, adminId);
    }

    private void requireAdmin(Long adminId) {
        if (adminId == null) {
            throw new BusinessException("操作人不能为空");
        }
    }

    private void requirePending(Long id) {
        MallGoods goods = id == null ? null : goodsMapper.selectByIdForShare(id);
        if (goods == null) {
            throw new BusinessException("商品不存在");
        }
        if (goods.getStatus() == null || goods.getStatus() != MallGoodsStatus.PENDING) {
            throw new BusinessException("该商品不在待审核状态");
        }
    }

    private void casReview(Long id, int target, String reason, Long adminId) {
        int rows = goodsMapper.update(null, Wrappers.<MallGoods>lambdaUpdate()
                .eq(MallGoods::getId, id)
                .eq(MallGoods::getStatus, MallGoodsStatus.PENDING)
                .set(MallGoods::getStatus, target)
                .set(MallGoods::getRejectReason, reason)
                .set(MallGoods::getReviewBy, adminId)
                .set(MallGoods::getReviewTime, LocalDateTime.now())
                .set(MallGoods::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("该商品不在待审核状态");
        }
    }
}
