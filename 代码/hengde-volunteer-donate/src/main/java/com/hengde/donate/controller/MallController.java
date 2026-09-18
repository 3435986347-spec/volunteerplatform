package com.hengde.donate.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.dto.MallOrderPayDTO;
import com.hengde.donate.dto.MallOrderPlaceDTO;
import com.hengde.donate.dto.MallOrderVerifyDTO;
import com.hengde.donate.dto.MallReviewDTO;
import com.hengde.donate.service.MallCouponService;
import com.hengde.donate.service.MallExchangeRuleService;
import com.hengde.donate.service.MallGoodsService;
import com.hengde.donate.service.MallOrderService;
import com.hengde.donate.service.MallReviewService;
import com.hengde.donate.service.MallVerifierService;
import com.hengde.donate.vo.ExchangeRecordVO;
import com.hengde.donate.vo.ExchangeRuleVO;
import com.hengde.donate.vo.MallCouponGrantVO;
import com.hengde.donate.vo.MallGoodsVO;
import com.hengde.donate.vo.MallOrderVO;
import com.hengde.donate.vo.MallReviewVO;
import com.hengde.trade.vo.TradeVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-积分商城（{@code /v/donate}）。
 *
 * <p>鉴权=<b>仅需登录</b>（由 {@code /v/**} 路由过滤器拦截）；商品与兑换是给全体志愿者用的，
 * 不挂权限点。核销入口例外——它要求调用者是核销员，由服务层 {@code verifyByVerifier} 判定。</p>
 *
 * <p><b>志愿者 id 一律取自登录态、不接受入参</b>——与积分中心、勋章同一纪律。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-积分商城")
@RestController
@RequestMapping("/v/donate")
public class MallController {

    private MallGoodsService goodsService;
    private MallOrderService orderService;
    private MallReviewService reviewService;
    private MallExchangeRuleService exchangeRuleService;
    private MallCouponService couponService;
    private MallVerifierService verifierService;

    @Autowired
    public void setExchangeRuleService(MallExchangeRuleService exchangeRuleService) {
        this.exchangeRuleService = exchangeRuleService;
    }

    @Autowired
    public void setGoodsService(MallGoodsService goodsService) {
        this.goodsService = goodsService;
    }

    @Autowired
    public void setOrderService(MallOrderService orderService) {
        this.orderService = orderService;
    }

    @Autowired
    public void setReviewService(MallReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Autowired
    public void setCouponService(MallCouponService couponService) {
        this.couponService = couponService;
    }

    @Autowired
    public void setVerifierService(MallVerifierService verifierService) {
        this.verifierService = verifierService;
    }

    @Operation(summary = "商品列表（仅已上架且未隐藏）")
    @GetMapping("/goods")
    public Result<PageResult<MallGoodsVO>> goods(
            PageQuery query,
            @Parameter(description = "按商品名模糊") @RequestParam(required = false) String keyword) {
        return Result.ok(goodsService.listForVolunteer(query, keyword));
    }

    @Operation(summary = "商品详情（含规格、赞助方、评价摘要、是否需持卷兑换）")
    @GetMapping("/goods/{id}")
    public Result<MallGoodsVO> goodsDetail(@PathVariable Long id) {
        return Result.ok(goodsService.detailForVolunteer(id));
    }

    @Operation(summary = "该商品的全部评价")
    @GetMapping("/goods/{id}/reviews")
    public Result<PageResult<MallReviewVO>> goodsReviews(@PathVariable Long id, PageQuery query) {
        return Result.ok(reviewService.listByGoods(id, query));
    }

    @Operation(summary = "我的兑换（status 可筛选 0待审核/1待领取·待发货/2已驳回/3已领取·已签收/4已取消/5待支付/6已发货）")
    @GetMapping("/orders")
    public Result<PageResult<MallOrderVO>> myOrders(
            PageQuery query,
            @Parameter(description = "状态筛选，不传=全部") @RequestParam(required = false) Integer status) {
        return Result.ok(orderService.listMine(StpUtil.getLoginIdAsLong(), query, status));
    }

    @Operation(summary = "下单兑换（下单即扣分并占库存；可选用一张卷，一单一卷；可选快递——快递费现金或积分抵扣；有现金要付的落「待支付」）")
    @PostMapping("/orders")
    public Result<MallOrderVO> place(@Valid @RequestBody MallOrderPlaceDTO dto) {
        Long volunteerId = StpUtil.getLoginIdAsLong();
        Long orderId = orderService.placeOrder(volunteerId, dto).getId();
        return Result.ok(orderService.detailMine(orderId, volunteerId));
    }

    @Operation(summary = "发起付款（仅待支付的单；传 wx.login 的 code，返回唤起小程序支付的参数）")
    @PostMapping("/orders/{id}/pay")
    public Result<TradeVOs.Prepay> pay(@PathVariable Long id, @Valid @RequestBody MallOrderPayDTO dto) {
        return Result.ok(orderService.pay(id, StpUtil.getLoginIdAsLong(), dto.getCode()));
    }

    @Operation(summary = "确认收货（仅快递且已发货的单）")
    @PostMapping("/orders/{id}/receive")
    public Result<Void> receive(@PathVariable Long id) {
        orderService.confirmReceipt(id, StpUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "我的兑换详情（待领取时带取货码与条码图）")
    @GetMapping("/orders/{id}")
    public Result<MallOrderVO> orderDetail(@PathVariable Long id) {
        return Result.ok(orderService.detailMine(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "取消兑换（退分 + 还库存 + 还卷；待支付的单先关交易单；已付款的单须联系协会）")
    @DeleteMapping("/orders/{id}")
    public Result<Void> cancel(@PathVariable Long id) {
        orderService.cancel(id, StpUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "评价（须是本人已领取的兑换单，一单一评）")
    @PostMapping("/orders/{id}/reviews")
    public Result<Long> review(@PathVariable Long id, @Valid @RequestBody MallReviewDTO dto) {
        return Result.ok(reviewService.submit(StpUtil.getLoginIdAsLong(), id, dto));
    }

    @Operation(summary = "我的评价")
    @GetMapping("/reviews/mine")
    public Result<PageResult<MallReviewVO>> myReviews(PageQuery query) {
        return Result.ok(reviewService.listMine(StpUtil.getLoginIdAsLong(), query));
    }

    @Operation(summary = "兑换规则（文字 + 图片，Row 8 C）")
    @GetMapping("/exchange-rules")
    public Result<ExchangeRuleVO> exchangeRules() {
        return Result.ok(exchangeRuleService.get());
    }

    @Operation(summary = "全部兑换记录（Row 8 C「全部人的」；只放姓名/商品/时间，不含单号与取货码）")
    @GetMapping("/exchange-records")
    public Result<PageResult<ExchangeRecordVO>> exchangeRecords(PageQuery query) {
        return Result.ok(orderService.listExchangeRecords(query));
    }

    @Operation(summary = "我的卷（status：0可用/1已使用/2已作废/3已过期，不传=全部）")
    @GetMapping("/coupons/mine")
    public Result<PageResult<MallCouponGrantVO>> myCoupons(
            PageQuery query,
            @Parameter(description = "0可用/1已使用/2已作废/3已过期，不传=全部") @RequestParam(required = false) Integer status) {
        return Result.ok(couponService.listMine(StpUtil.getLoginIdAsLong(), query, status));
    }

    /**
     * 下单前选卷用。适用判定与下单同一处实现——这里说能用，下单就不会报不适用。
     */
    @Operation(summary = "这件规格我此刻能用的卷（先到期的排前面）")
    @GetMapping("/coupons/usable")
    public Result<List<MallCouponGrantVO>> usableCoupons(
            @Parameter(description = "规格 id") @RequestParam Long specId) {
        return Result.ok(couponService.listUsableForSpec(StpUtil.getLoginIdAsLong(), specId));
    }

    /** 小程序据此决定是否显示「扫一扫核销」入口——仅 UX，核销接口本身另有兜底。 */
    @Operation(summary = "我是不是核销员")
    @GetMapping("/verifiers/me")
    public Result<Boolean> amIVerifier() {
        return Result.ok(verifierService.isVerifier(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "核销员扫码核销取货码（CAS，一次性；不能核销自己的单）")
    @PostMapping("/orders/verify")
    public Result<MallOrderVO> verify(@Valid @RequestBody MallOrderVerifyDTO dto) {
        return Result.ok(orderService.verifyByVerifier(dto.getCode(), StpUtil.getLoginIdAsLong()));
    }
}
