package com.hengde.donate.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.dto.ShipmentRegisterDTO;
import com.hengde.donate.service.WishService;
import com.hengde.donate.vo.DonateFlowVOs;
import com.hengde.donate.vo.WishVOs;
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
 * 志愿者端-圆梦微心愿（{@code /v/donate/wishes}，V3 微心愿批）。
 *
 * <p>仅需登录；志愿者 id 一律取自登录态。看心愿另要求已验手机号、认领另要求已实名（服务层判定）。
 * 寄出的包裹之后走捐书那几条通用接口（{@code /v/donate/shipments/{id}} 看详情 / 取消 / 交退回地址 / 看物流）。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-圆梦微心愿")
@RestController
@RequestMapping("/v/donate")
public class WishController {

    private WishService wishService;

    @Autowired
    public void setWishService(WishService wishService) {
        this.wishService = wishService;
    }

    @Operation(summary = "心愿列表：tab 0 心愿池 / 1 已认领 / 2 已实现（姓名、学校打 *；需已验手机号）")
    @GetMapping("/wishes")
    public Result<PageResult<WishVOs.Wish>> list(@RequestParam(required = false) Integer tab,
                                                 @RequestParam(required = false) String keyword,
                                                 PageQuery query) {
        return Result.ok(wishService.listForVolunteer(StpUtil.getLoginIdAsLong(), tab, keyword, query));
    }

    @Operation(summary = "微心愿中心：我的认领 + 寄出的包裹、物资与流转轨迹（Row 35）")
    @GetMapping("/wishes/mine")
    public Result<PageResult<WishVOs.Claim>> mine(PageQuery query) {
        return Result.ok(wishService.myClaims(StpUtil.getLoginIdAsLong(), query));
    }

    @Operation(summary = "心愿详情（认领人返回全部资料与物资接收地址，其他人打 *）")
    @GetMapping("/wishes/{id}")
    public Result<WishVOs.Wish> detail(@PathVariable Long id) {
        return Result.ok(wishService.detailForVolunteer(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "认领（须已实名志愿者）")
    @PostMapping("/wishes/{id}/claim")
    public Result<WishVOs.Wish> claim(@PathVariable Long id) {
        return Result.ok(wishService.claim(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "取消认领（物资已寄出且未取消时不可取消）")
    @DeleteMapping("/wishes/{id}/claim")
    public Result<Void> cancelClaim(@PathVariable Long id) {
        wishService.cancelClaim(id, StpUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "为认领的心愿登记寄出物资（录入物资 + 快递单号，一次提交）")
    @PostMapping("/wishes/{id}/shipments")
    public Result<DonateFlowVOs.Shipment> registerShipment(@PathVariable Long id,
                                                           @Valid @RequestBody ShipmentRegisterDTO dto) {
        return Result.ok(wishService.registerShipment(id, StpUtil.getLoginIdAsLong(), dto));
    }
}
