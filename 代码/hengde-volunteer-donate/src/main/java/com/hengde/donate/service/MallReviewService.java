package com.hengde.donate.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hengde.auth.service.VolunteerQueryService;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.donate.constant.MallOrderStatus;
import com.hengde.donate.dao.MallGoodsReviewMapper;
import com.hengde.donate.dao.MallOrderMapper;
import com.hengde.donate.dto.MallReviewDTO;
import com.hengde.donate.entity.MallGoodsReview;
import com.hengde.donate.entity.MallOrder;
import com.hengde.donate.vo.MallReviewVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品评价（Row 8 C「商品的评价 / 全部商品评价 / 我的评价」）。
 *
 * <h2>资格闸门：必须真的兑换过</h2>
 *
 * <p>需求没写谁能评。比照 {@code AttendanceService.submitReview} 那条「<b>须实际签到</b>才能评活动」
 * 的形状——那里以 {@code check_in_time} 为参加凭据，挡掉被负责人补建考勤行的未到场者；
 * 这里以<b>一张属于本人的、已领取的兑换单</b>为凭据。没有闸门的话，
 * 商品评价区就是一块任何登录用户都能写字的公共留言板。</p>
 *
 * <h2>粒度：唯一键建在<b>订单</b>上，不是「志愿者 + 商品」上</h2>
 *
 * <p>这是个要显式做的决定，不能让它只活在某个索引定义里：</p>
 * <ul>
 *   <li>建在 <b>(志愿者, 商品)</b> 上——同一个人兑换两次，第二次<b>就评不了了</b>，
 *       而他确实又消费了一次，凭什么不让他说话；</li>
 *   <li>建在 <b>订单</b> 上（{@code uk_order}）——<b>一单一评</b>：兑换几次就能评几次，
 *       每次都对应一笔真实消费，且天然防住了重复提交。</li>
 * </ul>
 *
 * <p>「防重复提交」是这个键的<b>附带</b>效果而不是目的，所以它由数据库唯一键保证、
 * 不是靠「先查一下有没有评过」——后者在并发下必漏（同一族的教训：勋章 {@code uk_active_grant}、
 * 证书 {@code uk_slot_cert}、积分 {@code uk_source}）。</p>
 *
 * @author hengde
 */
@Service
public class MallReviewService {

    private MallGoodsReviewMapper reviewMapper;
    private MallOrderMapper orderMapper;
    private VolunteerQueryService volunteerQueryService;

    @Autowired
    public void setReviewMapper(MallGoodsReviewMapper reviewMapper) {
        this.reviewMapper = reviewMapper;
    }

    @Autowired
    public void setOrderMapper(MallOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Autowired
    public void setVolunteerQueryService(VolunteerQueryService volunteerQueryService) {
        this.volunteerQueryService = volunteerQueryService;
    }

    /**
     * 提交评价。
     *
     * <p><b>评的是哪个商品由订单说了算</b>，不接受入参——否则拿着自己的一张兑换单就能去评别人的商品。
     * 与「志愿者 id 取自登录态」是同一条纪律：<b>凡是能从服务端推出来的，就不要让客户端送。</b></p>
     *
     * @param volunteerId 评价人（取自登录态）
     * @param orderId     兑换单 id——资格凭据
     * @param dto         评分与内容
     * @return 评价 id
     */
    public Long submit(Long volunteerId, Long orderId, MallReviewDTO dto) {
        if (volunteerId == null || orderId == null) {
            throw new BusinessException("参数不完整");
        }
        MallOrder order = orderMapper.selectById(orderId);
        // 归属不符与不存在返回同一句话，防按 id 枚举
        if (order == null || !order.getVolunteerId().equals(volunteerId)) {
            throw new BusinessException("兑换单不存在");
        }
        if (order.getStatus() == null || order.getStatus() != MallOrderStatus.PICKED) {
            throw new BusinessException("领取之后才能评价");
        }
        if (dto.getRating() == null || dto.getRating() < 1 || dto.getRating() > 5) {
            throw new BusinessException("评分为 1~5");
        }
        if (dto.getContent() != null && dto.getContent().length() > 512) {
            throw new BusinessException("评价内容不超过 512 字");
        }

        MallGoodsReview review = new MallGoodsReview();
        review.setGoodsId(order.getGoodsId());
        review.setOrderId(orderId);
        review.setVolunteerId(volunteerId);
        review.setRating(dto.getRating());
        review.setContent(dto.getContent());
        review.setStatus(1);
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // 撞的一定是 uk_order——本表只有这一个唯一键
            throw new BusinessException("该兑换单已评价过");
        }
        return review.getId();
    }

    /** 某商品的全部评价（仅正常项，逻辑删除自动排除）。 */
    public PageResult<MallReviewVO> listByGoods(Long goodsId, PageQuery query) {
        IPage<MallGoodsReview> page = reviewMapper.selectPage(query.toPage(),
                Wrappers.<MallGoodsReview>lambdaQuery()
                        .eq(MallGoodsReview::getGoodsId, goodsId)
                        .eq(MallGoodsReview::getStatus, 1)
                        .orderByDesc(MallGoodsReview::getId));
        return convert(page);
    }

    /** 我的评价。 */
    public PageResult<MallReviewVO> listMine(Long volunteerId, PageQuery query) {
        IPage<MallGoodsReview> page = reviewMapper.selectPage(query.toPage(),
                Wrappers.<MallGoodsReview>lambdaQuery()
                        .eq(MallGoodsReview::getVolunteerId, volunteerId)
                        .orderByDesc(MallGoodsReview::getId));
        return convert(page);
    }

    /**
     * 后台下架不当评价（逻辑删除）。与活动留言的下架同形态，不新增权限点。
     */
    public void delist(Long id) {
        if (id == null || reviewMapper.deleteById(id) != 1) {
            throw new BusinessException("评价不存在");
        }
    }

    /**
     * 补齐评价人姓名与商品名快照。
     *
     * <p>商品名/规格名取<b>订单上的快照</b>而不是回查商品表：商品可能已改名或被删，
     * 「我的评价」列表仍要说得出当初评的是什么。</p>
     */
    private PageResult<MallReviewVO> convert(IPage<MallGoodsReview> page) {
        List<MallGoodsReview> rows = page.getRecords();
        Map<Long, String> names = volunteerQueryService.listNamesByIds(
                rows.stream().map(MallGoodsReview::getVolunteerId).collect(Collectors.toSet()));
        Map<Long, MallOrder> orders = rows.isEmpty() ? Map.of()
                : orderMapper.selectByIds(rows.stream().map(MallGoodsReview::getOrderId).toList())
                .stream().collect(Collectors.toMap(MallOrder::getId, o -> o, (a, b) -> a));
        return PageResult.of(page.convert(r -> {
            MallReviewVO vo = new MallReviewVO();
            vo.setId(r.getId());
            vo.setGoodsId(r.getGoodsId());
            vo.setRating(r.getRating());
            vo.setContent(r.getContent());
            vo.setVolunteerName(names.get(r.getVolunteerId()));
            vo.setCreateTime(r.getCreateTime());
            MallOrder order = orders.get(r.getOrderId());
            if (order != null) {
                vo.setGoodsName(order.getGoodsName());
                vo.setSpecName(order.getSpecName());
            }
            return vo;
        }));
    }

}
