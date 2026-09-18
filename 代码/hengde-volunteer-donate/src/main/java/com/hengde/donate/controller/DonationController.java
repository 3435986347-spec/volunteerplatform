package com.hengde.donate.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.constant.DonationFlow;
import com.hengde.donate.dto.DonationDTOs;
import com.hengde.donate.service.DonateRecordService;
import com.hengde.donate.service.DonationService;
import com.hengde.donate.vo.DonationVOs;
import com.hengde.trade.vo.TradeVOs;
import io.swagger.v3.oas.annotations.Operation;
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

/**
 * 志愿者端-捐款（{@code /v/donate}，V3 捐款批）。捐款人 id 一律取登录态。
 *
 * @author hengde
 */
@Tag(name = "志愿者端-捐款")
@RestController
@RequestMapping("/v/donate")
public class DonationController {

    private DonationService donationService;
    private DonateRecordService recordService;

    @Autowired
    public void setRecordService(DonateRecordService recordService) {
        this.recordService = recordService;
    }

    @Autowired
    public void setDonationService(DonationService donationService) {
        this.donationService = donationService;
    }

    @Operation(summary = "众筹捐款（金额自填；传 wx.login 的 code；返回捐款记录与唤起支付的参数）")
    @PostMapping("/crowdfunds/{id}/donations")
    public Result<DonationVOs.Created> donateToCrowdfund(@PathVariable Long id,
                                                         @Valid @RequestBody DonationDTOs.CrowdfundDonate dto) {
        return Result.ok(donationService.donateToCrowdfund(id, StpUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "结对捐款（付「认捐额 − 已付」，须已登记结对；一条结对至多一笔待支付）")
    @PostMapping("/pair-projects/{id}/donations")
    public Result<DonationVOs.Created> donateToPair(@PathVariable Long id,
                                                    @Valid @RequestBody DonationDTOs.PairDonate dto) {
        return Result.ok(donationService.donateToPair(id, StpUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "众筹项目捐赠记录（公开；只列已到账的，姓名打码）")
    @GetMapping("/crowdfunds/{id}/donations")
    public Result<PageResult<DonationVOs.PublicRecord>> crowdfundRecords(@PathVariable Long id, PageQuery query) {
        return Result.ok(donationService.publicRecords(DonationFlow.BIZ_CROWDFUND, id, query));
    }

    @Operation(summary = "结对项目捐赠记录（Row 10「项目捐赠记录」；只列已到账的，姓名打码）")
    @GetMapping("/pair-projects/{id}/donations")
    public Result<PageResult<DonationVOs.PublicRecord>> pairRecords(@PathVariable Long id, PageQuery query) {
        return Result.ok(donationService.publicRecords(DonationFlow.BIZ_PAIR, id, query));
    }

    @Operation(summary = "我的捐赠记录（Row 33：众筹的捐款与捐物合成一条时间线；kind 0全部 / 1捐款 / 2捐物）")
    @GetMapping("/donations/mine")
    public Result<PageResult<DonationVOs.MyRecord>> mine(@RequestParam(required = false) Integer kind, PageQuery query) {
        return Result.ok(recordService.mine(StpUtil.getLoginIdAsLong(), kind, query));
    }

    @Operation(summary = "我的一笔捐款详情")
    @GetMapping("/donations/{id}")
    public Result<DonationVOs.Donation> detail(@PathVariable Long id) {
        return Result.ok(donationService.detailMine(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "重新发起付款（仅待支付；复用活的交易单）")
    @PostMapping("/donations/{id}/pay")
    public Result<TradeVOs.Prepay> pay(@PathVariable Long id, @Valid @RequestBody DonationDTOs.Pay dto) {
        return Result.ok(donationService.pay(id, StpUtil.getLoginIdAsLong(), dto.getCode()));
    }

    @Operation(summary = "取消待支付的捐款（先关交易单；刚付款的不能取消）")
    @DeleteMapping("/donations/{id}")
    public Result<Void> cancel(@PathVariable Long id) {
        donationService.cancel(id, StpUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
