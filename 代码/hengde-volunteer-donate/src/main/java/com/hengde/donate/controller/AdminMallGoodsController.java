package com.hengde.donate.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.constant.PermissionCode;
import com.hengde.donate.dto.GoodsDisplayDTO;
import com.hengde.donate.dto.MallGoodsSaveDTO;
import com.hengde.donate.dto.RejectReasonDTO;
import com.hengde.donate.service.MallGoodsService;
import com.hengde.donate.service.MallReviewService;
import com.hengde.donate.vo.MallGoodsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 管理端-积分商品（{@code /a/donate/goods}）。
 *
 * <p>录入与审核分属两个权限点，好让协会<b>能够</b>分给不同的人；<b>但这不等于系统禁止自审</b>
 * （同一账号可兼有两点，超管通配全有），见 {@link PermissionCode#DONATE_GOODS_AUDIT}。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-积分商品")
@RestController
@RequestMapping("/a/donate")
public class AdminMallGoodsController {

    private MallGoodsService goodsService;
    private MallReviewService reviewService;

    @Autowired
    public void setGoodsService(MallGoodsService goodsService) {
        this.goodsService = goodsService;
    }

    @Autowired
    public void setReviewService(MallReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 列表放宽为「管理权 <b>或</b> 审核权」——只有审核权却读不到列表就只能盲审。
     * 同勋章第 3 批评审补正的第 ④ 条。
     */
    @Operation(summary = "商品列表（含草稿/待审/停用/隐藏，status 可筛选）")
    @SaCheckPermission(value = {PermissionCode.DONATE_GOODS, PermissionCode.DONATE_GOODS_AUDIT},
            mode = SaMode.OR, type = "admin")
    @GetMapping("/goods")
    public Result<PageResult<MallGoodsVO>> list(
            PageQuery query,
            @Parameter(description = "按商品名模糊") @RequestParam(required = false) String keyword,
            @Parameter(description = "状态筛选，不传=全部") @RequestParam(required = false) Integer status) {
        return Result.ok(goodsService.listForAdmin(query, keyword, status));
    }

    @Operation(summary = "商品详情（不限状态）")
    @SaCheckPermission(value = {PermissionCode.DONATE_GOODS, PermissionCode.DONATE_GOODS_AUDIT},
            mode = SaMode.OR, type = "admin")
    @GetMapping("/goods/{id}")
    public Result<MallGoodsVO> detail(@PathVariable Long id) {
        return Result.ok(goodsService.detailForAdmin(id));
    }

    @Operation(summary = "新增商品（落草稿，图片走 /a/files/upload?dir=goods）")
    @SaCheckPermission(value = PermissionCode.DONATE_GOODS, type = "admin")
    @PostMapping("/goods")
    public Result<Long> create(@Valid @RequestBody MallGoodsSaveDTO dto) {
        return Result.ok(goodsService.create(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "修改商品（已过审的改完退回待审；审核中的不可改）")
    @SaCheckPermission(value = PermissionCode.DONATE_GOODS, type = "admin")
    @PutMapping("/goods/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody MallGoodsSaveDTO dto) {
        goodsService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "排序 / 隐藏（纯展示，不触发重审）")
    @SaCheckPermission(value = PermissionCode.DONATE_GOODS, type = "admin")
    @PatchMapping("/goods/{id}/display")
    public Result<Void> display(@PathVariable Long id, @Valid @RequestBody GoodsDisplayDTO dto) {
        goodsService.updateDisplay(id, dto.getSort(), dto.getHidden());
        return Result.ok();
    }

    @Operation(summary = "删除商品（逻辑删除，连同规格；历史订单有快照不受影响）")
    @SaCheckPermission(value = PermissionCode.DONATE_GOODS, type = "admin")
    @DeleteMapping("/goods/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        goodsService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "提交审核（仅草稿与驳回稿可提交）")
    @SaCheckPermission(value = PermissionCode.DONATE_GOODS, type = "admin")
    @PostMapping("/goods/{id}/submit")
    public Result<Void> submit(@PathVariable Long id) {
        goodsService.submit(id);
        return Result.ok();
    }

    @Operation(summary = "审核通过 → 已上架")
    @SaCheckPermission(value = PermissionCode.DONATE_GOODS_AUDIT, type = "admin")
    @PostMapping("/goods/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        goodsService.approve(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "审核驳回")
    @SaCheckPermission(value = PermissionCode.DONATE_GOODS_AUDIT, type = "admin")
    @PostMapping("/goods/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody RejectReasonDTO dto) {
        goodsService.reject(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "下架不当评价（逻辑删除，不新增权限点）")
    @SaCheckPermission(value = PermissionCode.DONATE_GOODS, type = "admin")
    @DeleteMapping("/reviews/{id}")
    public Result<Void> delistReview(@PathVariable Long id) {
        reviewService.delist(id);
        return Result.ok();
    }
}
