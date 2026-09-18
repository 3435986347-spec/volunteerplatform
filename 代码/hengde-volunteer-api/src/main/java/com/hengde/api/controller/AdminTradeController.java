package com.hengde.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.trade.constant.PermissionCode;
import com.hengde.trade.constant.TradeFlow;
import com.hengde.trade.dto.TradeDTOs;
import com.hengde.trade.entity.TradeOrder;
import com.hengde.trade.service.TradeOrderService;
import com.hengde.trade.service.TradeReconcileService;
import com.hengde.trade.service.TradeRefundService;
import com.hengde.trade.vo.TradeVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 管理端-收付（{@code /a/trade}，V3 trade 批）。
 *
 * <p><b>控制器落在 api 而不是 trade 模块</b>（V3规划 D1 的规矩 1）：取操作人要用 {@code StpAdminUtil}，
 * 而它在 auth；trade 若为此依赖 auth，日后 auth 要查会员费缴纳状态就会与 trade 成环。
 * 故 trade 保持 common-only，它的 service 收 {@code operatorId} 并硬校验非空——
 * <b>这里就是唯一给它传操作人的地方</b>。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-收付")
@RestController
@RequestMapping("/a/trade")
public class AdminTradeController {

    private TradeOrderService orderService;
    private TradeRefundService refundService;
    private TradeReconcileService reconcileService;

    @Autowired
    public void setOrderService(TradeOrderService orderService) {
        this.orderService = orderService;
    }

    @Autowired
    public void setRefundService(TradeRefundService refundService) {
        this.refundService = refundService;
    }

    @Autowired
    public void setReconcileService(TradeReconcileService reconcileService) {
        this.reconcileService = reconcileService;
    }

    @Operation(summary = "交易单列表（按业务类型 / 状态 / 创建时间筛选）")
    @SaCheckPermission(value = PermissionCode.TRADE_ORDER, type = "admin")
    @GetMapping("/orders")
    public Result<PageResult<TradeVOs.Order>> list(
            @RequestParam(required = false) Integer bizType,
            @RequestParam(required = false) Integer status,
            @Parameter(description = "起（含）。**用 ISO 的 T 分隔**，如 2026-09-01T00:00:00")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "止（不含）")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            PageQuery query) {
        return Result.ok(orderService.list(query, bizType, status, from, to));
    }

    @Operation(summary = "交易单详情（含支付与退款流水）")
    @SaCheckPermission(value = PermissionCode.TRADE_ORDER, type = "admin")
    @GetMapping("/orders/{id}")
    public Result<TradeVOs.Order> detail(@PathVariable Long id) {
        return Result.ok(orderService.detail(id));
    }

    @Operation(summary = "主动查单并同步本地（**本地与渠道不一致时以渠道为准**）")
    @SaCheckPermission(value = PermissionCode.TRADE_ORDER, type = "admin")
    @PostMapping("/orders/{id}/query")
    public Result<TradeVOs.Order> query(@PathVariable Long id) {
        TradeOrder order = orderService.requireById(id);
        orderService.queryAndSync(order.getOutTradeNo(), TradeFlow.SOURCE_QUERY);
        return Result.ok(orderService.detail(id));
    }

    @Operation(summary = "关单（仅待支付；已支付的关掉等于把收到的钱从账上抹掉）")
    @SaCheckPermission(value = PermissionCode.TRADE_ORDER, type = "admin")
    @PostMapping("/orders/{id}/close")
    public Result<Void> close(@PathVariable Long id) {
        orderService.close(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "退款（不填金额=整单退；原因必填）")
    @SaCheckPermission(value = PermissionCode.TRADE_REFUND, type = "admin")
    @PostMapping("/orders/{id}/refund")
    public Result<String> refund(@PathVariable Long id, @Valid @RequestBody TradeDTOs.Refund dto) {
        return Result.ok(refundService.refund(id, dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "手动对账（**第四道，不是唯一那道**；范围必须显式给出，已支付与已关闭两侧都核，结果落库）")
    @SaCheckPermission(value = PermissionCode.TRADE_ORDER, type = "admin")
    @PostMapping("/reconciliations")
    public Result<TradeVOs.Reconciliation> reconcile(
            @Parameter(description = "起（含），ISO 的 T 分隔") @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "止（不含）") @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return Result.ok(reconcileService.reconcileManually(from, to, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "对账记录列表（每日定时 + 手动，新的在前；不含差异明细）")
    @SaCheckPermission(value = PermissionCode.TRADE_ORDER, type = "admin")
    @GetMapping("/reconciliations")
    public Result<PageResult<TradeVOs.Reconciliation>> runs(
            @Parameter(description = "true = 只看有差异的") @RequestParam(required = false) Boolean onlyMismatch,
            @Parameter(description = "true = 只看「渠道未开通、没有核对」的") @RequestParam(required = false)
            Boolean onlySkipped,
            PageQuery query) {
        return Result.ok(reconcileService.listRuns(query, onlyMismatch, onlySkipped));
    }

    @Operation(summary = "对账记录详情（含逐条差异）")
    @SaCheckPermission(value = PermissionCode.TRADE_ORDER, type = "admin")
    @GetMapping("/reconciliations/{id}")
    public Result<TradeVOs.Reconciliation> runDetail(@PathVariable Long id) {
        return Result.ok(reconcileService.runDetail(id));
    }
}
