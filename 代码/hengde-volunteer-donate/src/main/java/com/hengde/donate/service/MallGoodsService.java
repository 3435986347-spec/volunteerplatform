package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.constant.MallGoodsStatus;
import com.hengde.donate.dao.MallGoodsMapper;
import com.hengde.donate.dao.MallGoodsReviewMapper;
import com.hengde.donate.dao.MallGoodsSpecMapper;
import com.hengde.donate.dto.MallGoodsSaveDTO;
import com.hengde.donate.dto.MallGoodsSpecDTO;
import com.hengde.donate.entity.MallGoods;
import com.hengde.donate.entity.MallGoodsReview;
import com.hengde.donate.entity.MallGoodsSpec;
import com.hengde.donate.vo.MallGoodsSpecVO;
import com.hengde.donate.vo.MallGoodsVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 积分商品管理与审核（Row 8 F「积分商品管理板块：审核、兑换」）。
 *
 * @author hengde
 */
@Service
public class MallGoodsService {

    private MallGoodsMapper goodsMapper;
    private MallGoodsSpecMapper specMapper;
    private MallGoodsReviewMapper reviewMapper;
    private MallCouponService couponService;

    @Autowired
    public void setGoodsMapper(MallGoodsMapper goodsMapper) {
        this.goodsMapper = goodsMapper;
    }

    @Autowired
    public void setSpecMapper(MallGoodsSpecMapper specMapper) {
        this.specMapper = specMapper;
    }

    @Autowired
    public void setReviewMapper(MallGoodsReviewMapper reviewMapper) {
        this.reviewMapper = reviewMapper;
    }

    @Autowired
    public void setCouponService(MallCouponService couponService) {
        this.couponService = couponService;
    }

    /**
     * 新增商品，落<b>草稿</b>态。
     *
     * <p><b>不落待审核</b>：草稿让录入的人可以分几次填完、传完图再提交，
     * 与勋章 {@code MedalService.create} 同一形状。提交是独立动作（{@link #submit}）。</p>
     *
     * @param dto     商品与规格
     * @param adminId 操作人（仅作非空校验，草稿不记审核人）
     * @return 商品 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(MallGoodsSaveDTO dto, Long adminId) {
        requireAdmin(adminId);
        if (dto.getSpecs() == null || dto.getSpecs().isEmpty()) {
            throw new BusinessException("请至少填写一个规格（库存与所需积分都挂在规格上）");
        }
        couponService.requireExists(dto.getRequireCouponId());
        MallGoods goods = new MallGoods();
        goods.setName(dto.getName());
        goods.setCoverUrl(dto.getCoverUrl());
        goods.setDetail(dto.getDetail());
        goods.setSponsorName(dto.getSponsorName());
        goods.setRequireCouponId(dto.getRequireCouponId());
        goods.setStatus(MallGoodsStatus.DRAFT);
        goods.setHidden(0);
        goods.setSort(0);
        goodsMapper.insert(goods);
        replaceSpecs(goods.getId(), dto.getSpecs());
        return goods.getId();
    }

    /**
     * 删除商品（逻辑删除，连同其规格）。
     *
     * <p><b>不校验有没有历史订单</b>，也不需要——订单已把商品名 / 规格名 / 所需积分快照下来了，
     * 删掉商品不会让历史订单变成空壳。这正是 D8 那三列买到的东西。</p>
     *
     * <p><b>规格必须一起软删</b>：漏掉的话，那些规格行仍会被 {@code deductStock} 的
     * {@code s.is_deleted = 0} 放行——虽然它的 JOIN 条件里还有 {@code g.is_deleted = 0} 兜着，
     * 但把「商品没了、规格还在」这种状态留在库里，下一个写查询的人就会踩到。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null || goodsMapper.deleteById(id) != 1) {
            throw new BusinessException("商品不存在");
        }
        specMapper.delete(Wrappers.<MallGoodsSpec>lambdaQuery().eq(MallGoodsSpec::getGoodsId, id));
    }

    /**
     * 修改商品。<b>改已过审的商品会把它退回待审核。</b>
     *
     * <p><b>为什么退回重审</b>：样式与价格是审核过的东西，改完还算「审核通过」等于给了一条
     * 绕过审核的路——先提交一版素净的过审，通过后再改成别的。商城这里更实：改的是
     * <b>价格、所需积分、赞助方</b>，卷批起还有<b>「必须持卷才能兑换」</b>——它决定谁能买。
     * 同 {@code MedalService.update} 的判断。</p>
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
     * @param values 新值（name/coverUrl/detail/sponsorName/requireCouponId，全量语义）
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, MallGoodsSaveDTO values) {
        doUpdate(id, values, null);
    }

    /**
     * 赞助企业修改自己的商品（V4 爱心企业批）：与后台修改同一套「改已过审的退回重审 / 审核中不许改」，
     * 另加<b>只能改本企业赞助的</b>（条件写在 UPDATE 的 WHERE 里）；赞助方名称与「必须持卷」不归企业改。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateForSponsor(Long id, Long enterpriseId, MallGoodsSaveDTO values) {
        if (enterpriseId == null) {
            throw new BusinessException("企业不能为空");
        }
        doUpdate(id, values, enterpriseId);
    }

    private void doUpdate(Long id, MallGoodsSaveDTO values, Long sponsorScope) {
        if (id == null) {
            throw new BusinessException("商品不存在");
        }
        if (!StringUtils.hasText(values.getName())) {
            throw new BusinessException("请填写商品名称");
        }
        if (sponsorScope == null) {
            couponService.requireExists(values.getRequireCouponId());
        }
        int rows = goodsMapper.update(null, Wrappers.<MallGoods>lambdaUpdate()
                .eq(MallGoods::getId, id)
                .eq(sponsorScope != null, MallGoods::getSponsorEnterpriseId, sponsorScope)
                // CAS：把「不许改审核中的商品」变成语句级的原子条件
                .ne(MallGoods::getStatus, MallGoodsStatus.PENDING)
                .set(MallGoods::getName, values.getName())
                .set(MallGoods::getCoverUrl, values.getCoverUrl())
                .set(MallGoods::getDetail, values.getDetail())
                // 赞助方名称只对【平台自营】的商品按表单写：企业赞助的，名称是企业名称的快照，
                // 后台在商品管理里顺手改（或清空）会让它与企业脱钩——条件写进 SET，与上面的 CAS 同一条语句
                .setSql(sponsorScope == null,
                        "sponsor_name = CASE WHEN sponsor_enterprise_id IS NULL THEN {0} ELSE sponsor_name END",
                        values.getSponsorName())
                // 显式 set（含 null）：取消「必须持卷」要能真的写成 NULL，updateById 会跳过 null
                .set(sponsorScope == null, MallGoods::getRequireCouponId, values.getRequireCouponId())
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
        // 规格：null=不动，非空=全量替换。改价格同样要退回重审——上面那条 CASE 已经做了
        if (values.getSpecs() != null) {
            if (values.getSpecs().isEmpty()) {
                throw new BusinessException("请至少保留一个规格");
            }
            replaceSpecs(id, values.getSpecs());
        }
    }

    /**
     * 全量替换规格：传了 id 的原地更新，没传的新增，库里有而这次没传的软删。
     *
     * <p><b>为什么不是「先全删再全插」</b>：那样每次改一下商品名都会让规格换一批新 id，
     * 而 {@code mall_order.spec_id} 指着它们——虽然订单有快照不至于显示错，
     * 但「这张单买的是哪一行规格」会断掉，还库存也就找不到人了。</p>
     *
     * <p><b>库存是直接覆盖的</b>：这是后台在盘点后填的准数，不是增量。
     * ⚠️ 它与下单侧的 {@code deductStock} 之间没有互斥——管理员在盘点时改库存、
     * 同一刻有人下单，后写的那个赢。这是<b>已知且可接受</b>的：库存的真值在货架上，
     * 后台改库存本来就是「以我为准」的动作。</p>
     */
    private void replaceSpecs(Long goodsId, List<MallGoodsSpecDTO> specs) {
        Set<String> names = new HashSet<>();
        for (MallGoodsSpecDTO dto : specs) {
            if (!names.add(dto.getName())) {
                throw new BusinessException("规格名重复：" + dto.getName());
            }
        }
        List<MallGoodsSpec> existing = specMapper.selectList(
                Wrappers.<MallGoodsSpec>lambdaQuery().eq(MallGoodsSpec::getGoodsId, goodsId));
        Map<Long, MallGoodsSpec> byId = existing.stream()
                .collect(Collectors.toMap(MallGoodsSpec::getId, x -> x, (a, b) -> a, LinkedHashMap::new));

        Set<Long> kept = new HashSet<>();
        int order = 0;
        for (MallGoodsSpecDTO dto : specs) {
            MallGoodsSpec row = new MallGoodsSpec();
            row.setGoodsId(goodsId);
            row.setName(dto.getName());
            row.setPoints(dto.getPoints());
            // 不填按 0（纯积分）写，<b>不留 null</b>：updateById 会跳过 null，把「改回纯积分」静默丢掉
            row.setCashFen(dto.getCashFen() == null ? 0 : dto.getCashFen());
            row.setStock(dto.getStock());
            row.setSort(dto.getSort() == null ? order : dto.getSort());
            if (dto.getId() != null && byId.containsKey(dto.getId())) {
                row.setId(dto.getId());
                specMapper.updateById(row);
                kept.add(dto.getId());
            } else if (dto.getId() != null) {
                throw new BusinessException("规格不属于该商品：" + dto.getId());
            } else {
                specMapper.insert(row);
            }
            order++;
        }
        List<Long> removed = byId.keySet().stream().filter(x -> !kept.contains(x)).toList();
        if (!removed.isEmpty()) {
            specMapper.deleteByIds(removed);
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

    // ---------------- 赞助企业（V4 爱心企业批） ----------------

    /**
     * 赞助企业自助新增商品（落草稿，提交后走同一个后台审核）。赞助方名称取企业名称快照，企业 id 记在 {@code sponsor_enterprise_id}；
     * 企业不能设「必须持卷」（卷是平台发的）。后台代企业发布也走这里。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createForSponsor(MallGoodsSaveDTO dto, Long enterpriseId, String enterpriseName) {
        if (enterpriseId == null || !StringUtils.hasText(enterpriseName)) {
            throw new BusinessException("企业不能为空");
        }
        if (dto.getSpecs() == null || dto.getSpecs().isEmpty()) {
            throw new BusinessException("请至少填写一个规格（库存与所需积分都挂在规格上）");
        }
        MallGoods goods = new MallGoods();
        goods.setName(dto.getName());
        goods.setCoverUrl(dto.getCoverUrl());
        goods.setDetail(dto.getDetail());
        goods.setSponsorEnterpriseId(enterpriseId);
        goods.setSponsorName(enterpriseName);
        goods.setSponsorSuspended(0);
        goods.setStatus(MallGoodsStatus.DRAFT);
        goods.setHidden(0);
        goods.setSort(0);
        goodsMapper.insert(goods);
        replaceSpecs(goods.getId(), dto.getSpecs());
        return goods.getId();
    }

    /** 赞助企业提交审核：只对本企业的草稿与驳回稿。 */
    public void submitForSponsor(Long id, Long enterpriseId) {
        int rows = goodsMapper.update(null, Wrappers.<MallGoods>lambdaUpdate()
                .eq(MallGoods::getId, id)
                .eq(MallGoods::getSponsorEnterpriseId, enterpriseId)
                .in(MallGoods::getStatus, MallGoodsStatus.DRAFT, MallGoodsStatus.REJECTED)
                .set(MallGoods::getStatus, MallGoodsStatus.PENDING)
                .set(MallGoods::getSubmitTime, LocalDateTime.now())
                .set(MallGoods::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("商品不存在或当前状态不可提交审核");
        }
    }

    /** 赞助企业删除自己的商品（连同规格；历史订单有快照不受影响）。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteForSponsor(Long id, Long enterpriseId) {
        if (id == null || goodsMapper.delete(Wrappers.<MallGoods>lambdaQuery()
                .eq(MallGoods::getId, id).eq(MallGoods::getSponsorEnterpriseId, enterpriseId)) != 1) {
            throw new BusinessException("商品不存在");
        }
        specMapper.delete(Wrappers.<MallGoodsSpec>lambdaQuery().eq(MallGoodsSpec::getGoodsId, id));
    }

    /** 赞助企业隐藏 / 显示自己的商品（Row 8 F「商品隐藏功能」；不触发重审）。 */
    public void hideForSponsor(Long id, Long enterpriseId, int hidden) {
        if (hidden != 0 && hidden != 1) {
            throw new BusinessException("隐藏标记只能是 0 或 1");
        }
        int rows = goodsMapper.update(null, Wrappers.<MallGoods>lambdaUpdate()
                .eq(MallGoods::getId, id)
                .eq(MallGoods::getSponsorEnterpriseId, enterpriseId)
                .set(MallGoods::getHidden, hidden)
                .set(MallGoods::getUpdateTime, LocalDateTime.now()));
        if (rows != 1) {
            throw new BusinessException("商品不存在");
        }
    }

    /** 赞助企业自己的商品（含草稿 / 待审核 / 驳回原因）。 */
    public PageResult<MallGoodsVO> listForSponsor(Long enterpriseId, PageQuery query, Integer status) {
        IPage<MallGoods> page = goodsMapper.selectPage(query.toPage(), Wrappers.<MallGoods>lambdaQuery()
                .eq(MallGoods::getSponsorEnterpriseId, enterpriseId)
                .eq(status != null, MallGoods::getStatus, status)
                .orderByDesc(MallGoods::getId));
        PageResult<MallGoodsVO> result = PageResult.of(page.convert(g -> toVO(g, true)));
        fill(result.getRecords());
        return result;
    }

    public MallGoodsVO detailForSponsor(Long id, Long enterpriseId) {
        MallGoods goods = id == null ? null : goodsMapper.selectById(id);
        if (goods == null || enterpriseId == null || !enterpriseId.equals(goods.getSponsorEnterpriseId())) {
            throw new BusinessException("商品不存在");
        }
        MallGoodsVO vo = toVO(goods, true);
        fill(List.of(vo));
        return vo;
    }

    /**
     * 整批置位「赞助企业当前不可用」（企业暂停 / 删除时 true，恢复时 false）。<b>必须在调用方事务里</b>，与企业状态迁移同成同败。
     *
     * @return 影响的商品数
     */
    public int setSponsorSuspended(Long enterpriseId, boolean suspended) {
        return goodsMapper.update(null, Wrappers.<MallGoods>lambdaUpdate()
                .eq(MallGoods::getSponsorEnterpriseId, enterpriseId)
                .set(MallGoods::getSponsorSuspended, suspended ? 1 : 0)
                .set(MallGoods::getUpdateTime, LocalDateTime.now()));
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

    // ---------------- 查询 ----------------

    /**
     * 志愿者端商品列表：<b>仅已上架且未隐藏</b>。
     *
     * <p>草稿 / 待审核 / 驳回 / 已停用一律不可见，与活动「仅已发布可见」同口径。</p>
     */
    public PageResult<MallGoodsVO> listForVolunteer(PageQuery query, String keyword) {
        return listForVolunteer(query, keyword, null);
    }

    /**
     * @param sponsorEnterpriseId 只看某个赞助企业的（企业主页「赞助商品」）；null＝全部
     */
    public PageResult<MallGoodsVO> listForVolunteer(PageQuery query, String keyword, Long sponsorEnterpriseId) {
        IPage<MallGoods> page = goodsMapper.selectPage(query.toPage(),
                Wrappers.<MallGoods>lambdaQuery()
                        .eq(MallGoods::getStatus, MallGoodsStatus.ON_SALE)
                        .eq(MallGoods::getHidden, 0)
                        // 赞助企业被暂停 / 删除：它的商品一并不可见（V78；下单那条 UPDATE 同样带着这个条件）
                        .eq(MallGoods::getSponsorSuspended, 0)
                        .eq(sponsorEnterpriseId != null, MallGoods::getSponsorEnterpriseId, sponsorEnterpriseId)
                        .like(StringUtils.hasText(keyword), MallGoods::getName, keyword)
                        .orderByAsc(MallGoods::getSort)
                        .orderByDesc(MallGoods::getId));
        PageResult<MallGoodsVO> result = PageResult.of(page.convert(g -> toVO(g, false)));
        fill(result.getRecords());
        return result;
    }

    /** 管理端商品列表：含草稿 / 待审核 / 已停用 / 已隐藏，可按状态筛选。 */
    public PageResult<MallGoodsVO> listForAdmin(PageQuery query, String keyword, Integer status) {
        IPage<MallGoods> page = goodsMapper.selectPage(query.toPage(),
                Wrappers.<MallGoods>lambdaQuery()
                        .eq(status != null, MallGoods::getStatus, status)
                        .like(StringUtils.hasText(keyword), MallGoods::getName, keyword)
                        .orderByDesc(MallGoods::getId));
        PageResult<MallGoodsVO> result = PageResult.of(page.convert(g -> toVO(g, true)));
        fill(result.getRecords());
        return result;
    }

    /** 志愿者端商品详情：同样只认已上架且未隐藏，草稿泄露与活动那条是同一类问题。 */
    public MallGoodsVO detailForVolunteer(Long id) {
        MallGoods goods = id == null ? null : goodsMapper.selectById(id);
        if (goods == null || goods.getStatus() == null
                || goods.getStatus() != MallGoodsStatus.ON_SALE
                || (goods.getHidden() != null && goods.getHidden() == 1)
                || (goods.getSponsorSuspended() != null && goods.getSponsorSuspended() == 1)) {
            throw new BusinessException("商品不存在");
        }
        MallGoodsVO vo = toVO(goods, false);
        fill(List.of(vo));
        return vo;
    }

    /** 管理端商品详情：不限状态。 */
    public MallGoodsVO detailForAdmin(Long id) {
        MallGoods goods = id == null ? null : goodsMapper.selectById(id);
        if (goods == null) {
            throw new BusinessException("商品不存在");
        }
        MallGoodsVO vo = toVO(goods, true);
        fill(List.of(vo));
        return vo;
    }

    /**
     * 批量补齐规格、评价聚合与「需持卷」卷名。<b>每类一次查库，不按行循环</b>——列表页 N+1 是本项目反复清理过的老问题
     * （小组列表、分队列表、报名列表都各记过一次）。
     */
    private void fill(List<MallGoodsVO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<Long> ids = records.stream().map(MallGoodsVO::getId).toList();

        Map<Long, List<MallGoodsSpecVO>> specs = specMapper.selectList(
                        Wrappers.<MallGoodsSpec>lambdaQuery()
                                .in(MallGoodsSpec::getGoodsId, ids)
                                .orderByAsc(MallGoodsSpec::getSort)
                                .orderByAsc(MallGoodsSpec::getId))
                .stream().collect(Collectors.groupingBy(MallGoodsSpec::getGoodsId,
                        Collectors.mapping(MallGoodsService::toSpecVO, Collectors.toList())));

        Map<Long, Map<String, Object>> stats = reviewStats(ids);

        Map<Long, String> couponNames = couponService.namesOf(records.stream()
                .map(MallGoodsVO::getRequireCouponId).filter(Objects::nonNull).collect(Collectors.toSet()));

        for (MallGoodsVO vo : records) {
            List<MallGoodsSpecVO> list = specs.getOrDefault(vo.getId(), Collections.emptyList());
            vo.setSpecs(list);
            vo.setMinPoints(list.stream().map(MallGoodsSpecVO::getPoints)
                    .filter(Objects::nonNull).min(Integer::compareTo).orElse(null));
            vo.setTotalStock(list.stream().map(MallGoodsSpecVO::getStock)
                    .filter(Objects::nonNull).mapToInt(Integer::intValue).sum());
            Map<String, Object> row = stats.get(vo.getId());
            vo.setReviewCount(row == null ? 0 : ((Number) row.get("cnt")).intValue());
            vo.setAvgRating(row == null ? null
                    : Math.round(((Number) row.get("avgRating")).doubleValue() * 10) / 10.0);
            if (vo.getRequireCouponId() != null) {
                vo.setRequireCouponName(couponNames.getOrDefault(vo.getRequireCouponId(), "指定卷"));
            }
        }
    }

    /** 评价条数与均分，一次 group by 取回。 */
    private Map<Long, Map<String, Object>> reviewStats(Collection<Long> goodsIds) {
        List<Map<String, Object>> rows = reviewMapper.selectMaps(Wrappers.<MallGoodsReview>query()
                .select("goods_id AS goodsId", "COUNT(*) AS cnt", "AVG(rating) AS avgRating")
                .in("goods_id", goodsIds)
                .eq("status", 1)
                .groupBy("goods_id"));
        Map<Long, Map<String, Object>> byGoods = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byGoods.put(((Number) row.get("goodsId")).longValue(), row);
        }
        return byGoods;
    }

    private static MallGoodsSpecVO toSpecVO(MallGoodsSpec spec) {
        MallGoodsSpecVO vo = new MallGoodsSpecVO();
        vo.setId(spec.getId());
        vo.setName(spec.getName());
        vo.setPoints(spec.getPoints());
        int cash = spec.getCashFen() == null ? 0 : spec.getCashFen();
        vo.setCashFen(cash);
        vo.setCashYuan(com.hengde.trade.constant.TradeFlow.yuan(cash));
        vo.setStock(spec.getStock());
        vo.setSort(spec.getSort());
        return vo;
    }

    /**
     * @param forAdmin true 时才带审核痕迹（驳回原因 / 审核人 / 审核时间）。
     *                 志愿者端看得到驳回原因等于把内部审核意见公开出去。
     */
    private MallGoodsVO toVO(MallGoods goods, boolean forAdmin) {
        MallGoodsVO vo = new MallGoodsVO();
        vo.setId(goods.getId());
        vo.setName(goods.getName());
        vo.setCoverUrl(goods.getCoverUrl());
        vo.setDetail(goods.getDetail());
        vo.setSponsorName(goods.getSponsorName());
        vo.setRequireCouponId(goods.getRequireCouponId());
        vo.setStatus(goods.getStatus());
        vo.setStatusLabel(MallGoodsStatus.labelOf(goods.getStatus()));
        vo.setHidden(goods.getHidden());
        vo.setSort(goods.getSort());
        vo.setCreateTime(goods.getCreateTime());
        if (forAdmin) {
            vo.setSponsorEnterpriseId(goods.getSponsorEnterpriseId());
            vo.setRejectReason(goods.getRejectReason());
            vo.setReviewBy(goods.getReviewBy());
            vo.setReviewTime(goods.getReviewTime());
        }
        return vo;
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
