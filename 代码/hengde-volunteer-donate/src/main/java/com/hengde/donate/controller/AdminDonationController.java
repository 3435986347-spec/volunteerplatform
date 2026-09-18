package com.hengde.donate.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.constant.PermissionCode;
import com.hengde.donate.dto.DonationDTOs;
import com.hengde.donate.service.DonationService;
import com.hengde.donate.vo.DonationVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端-捐款（{@code /a/donate/donations}，V3 捐款批）。
 *
 * <p><b>退款要两个权限都有</b>（项目管理 + 收付退款）：退款是把钱退出去，只有项目管理权的人不该单独做成这件事，
 * 与 trade 批「查看与退款分开」同一个口径；不为它新开权限点。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-捐款")
@RestController
@RequestMapping("/a/donate")
public class AdminDonationController {

    private DonationService donationService;

    @Autowired
    public void setDonationService(DonationService donationService) {
        this.donationService = donationService;
    }

    @Operation(summary = "捐款列表（按类型 / 项目 / 状态 / 开票状态；keyword 试单号、项目名、捐款人姓名或手机号）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @GetMapping("/donations")
    public Result<PageResult<DonationVOs.Donation>> list(
            PageQuery query,
            @Parameter(description = "2众筹捐款 / 3结对捐款") @RequestParam(required = false) Integer bizType,
            @RequestParam(required = false) Long projectId,
            @Parameter(description = "0待支付 / 1已到账 / 2已取消 / 3已退款") @RequestParam(required = false) Integer status,
            @Parameter(description = "0不需要 / 1待开 / 2已开") @RequestParam(required = false) Integer invoiceStatus,
            @RequestParam(required = false) String keyword) {
        return Result.ok(donationService.listForAdmin(query, bizType, projectId, status, invoiceStatus, keyword));
    }

    @Operation(summary = "退一笔已到账的捐款（先减回已筹、提交后原路退款；已开票的须先作废发票）")
    @SaCheckPermission(value = {PermissionCode.DONATE_PROJECT, com.hengde.trade.constant.PermissionCode.TRADE_REFUND},
            mode = SaMode.AND, type = "admin")
    @PostMapping("/donations/{id}/refund")
    public Result<Void> refund(@PathVariable Long id, @Valid @RequestBody DonationDTOs.Refund dto) {
        donationService.refund(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "登记开票（清单⑦只预留：发票在系统外开，这里记发票号；仅已到账且需要发票的）")
    @SaCheckPermission(value = PermissionCode.DONATE_PROJECT, type = "admin")
    @PostMapping("/donations/{id}/invoice")
    public Result<Void> invoice(@PathVariable Long id, @Valid @RequestBody DonationDTOs.Invoice dto) {
        donationService.markInvoiced(id, dto.getInvoiceNo(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
