package com.hengde.donate.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.constant.PermissionCode;
import com.hengde.donate.dto.MallCouponGrantDTO;
import com.hengde.donate.dto.MallCouponRevokeDTO;
import com.hengde.donate.dto.MallCouponSaveDTO;
import com.hengde.donate.service.MallCouponService;
import com.hengde.donate.vo.CouponGrantResultVO;
import com.hengde.donate.vo.MallCouponGrantVO;
import com.hengde.donate.vo.MallCouponVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端-卷（{@code /a/donate/coupons}、{@code /a/donate/coupon-grants}，V3 卷批）。
 *
 * <p>全部挂 {@code donate:coupon}——建卷与发卷同一个点，理由见 {@link PermissionCode#DONATE_COUPON}。</p>
 *
 * <p><b>没有删除入口，只能停用</b>：商品可能以 {@code require_coupon_id} 引用一张卷，
 * 删掉它会让那件商品永远没人能兑；停用只挡新发放，已发出的照常可用。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-积分商城卷")
@RestController
@RequestMapping("/a/donate")
public class AdminMallCouponController {

    private MallCouponService couponService;

    @Autowired
    public void setCouponService(MallCouponService couponService) {
        this.couponService = couponService;
    }

    @Operation(summary = "卷列表（带已发放 / 已使用张数）")
    @SaCheckPermission(value = PermissionCode.DONATE_COUPON, type = "admin")
    @GetMapping("/coupons")
    public Result<PageResult<MallCouponVO>> list(
            PageQuery query,
            @Parameter(description = "按卷名模糊") @RequestParam(required = false) String keyword,
            @Parameter(description = "1启用/0停用，不传=全部") @RequestParam(required = false) Integer status) {
        return Result.ok(couponService.listForAdmin(query, keyword, status));
    }

    @Operation(summary = "卷详情")
    @SaCheckPermission(value = PermissionCode.DONATE_COUPON, type = "admin")
    @GetMapping("/coupons/{id}")
    public Result<MallCouponVO> detail(@PathVariable Long id) {
        return Result.ok(couponService.detailForAdmin(id));
    }

    @Operation(summary = "新建卷（默认启用）")
    @SaCheckPermission(value = PermissionCode.DONATE_COUPON, type = "admin")
    @PostMapping("/coupons")
    public Result<Long> create(@Valid @RequestBody MallCouponSaveDTO dto) {
        return Result.ok(couponService.create(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "修改卷（只影响之后发出的卷，已发出的有条款快照）")
    @SaCheckPermission(value = PermissionCode.DONATE_COUPON, type = "admin")
    @PutMapping("/coupons/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody MallCouponSaveDTO dto) {
        couponService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "启用 / 停用（停用只挡新发放）")
    @SaCheckPermission(value = PermissionCode.DONATE_COUPON, type = "admin")
    @PatchMapping("/coupons/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @Parameter(description = "1启用/0停用") @RequestParam Integer status) {
        couponService.updateStatus(id, status);
        return Result.ok();
    }

    @Operation(summary = "发卷 / 批量发卷（按志愿者 id 或手机号；requestId 保幂等）")
    @SaCheckPermission(value = PermissionCode.DONATE_COUPON, type = "admin")
    @PostMapping("/coupons/{id}/grants")
    public Result<CouponGrantResultVO> grant(@PathVariable Long id, @Valid @RequestBody MallCouponGrantDTO dto) {
        return Result.ok(couponService.grant(id, dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "某张卷的发放记录（status：0可用/1已使用/2已作废/3已过期）")
    @SaCheckPermission(value = PermissionCode.DONATE_COUPON, type = "admin")
    @GetMapping("/coupons/{id}/grants")
    public Result<PageResult<MallCouponGrantVO>> grants(
            @PathVariable Long id, PageQuery query,
            @Parameter(description = "0可用/1已使用/2已作废/3已过期，不传=全部") @RequestParam(required = false) Integer status) {
        return Result.ok(couponService.listGrants(id, query, status));
    }

    @Operation(summary = "作废一张已发出的卷（仅未使用的可作废）")
    @SaCheckPermission(value = PermissionCode.DONATE_COUPON, type = "admin")
    @PostMapping("/coupon-grants/{id}/revoke")
    public Result<Void> revoke(@PathVariable Long id, @Valid @RequestBody MallCouponRevokeDTO dto) {
        couponService.revoke(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
