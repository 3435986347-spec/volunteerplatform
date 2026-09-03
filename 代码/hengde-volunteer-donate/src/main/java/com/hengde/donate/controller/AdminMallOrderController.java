package com.hengde.donate.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.constant.PermissionCode;
import com.hengde.donate.dto.MallOrderVerifyDTO;
import com.hengde.donate.dto.RejectReasonDTO;
import com.hengde.donate.service.MallOrderService;
import com.hengde.donate.vo.MallOrderVO;
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
 * 管理端-兑换单（{@code /a/donate/orders}）。
 *
 * @author hengde
 */
@Tag(name = "管理端-积分兑换单")
@RestController
@RequestMapping("/a/donate")
public class AdminMallOrderController {

    private MallOrderService orderService;

    @Autowired
    public void setOrderService(MallOrderService orderService) {
        this.orderService = orderService;
    }

    /** 只读权与审核权都能看列表——只有审核权却读不到队列就只能盲审。 */
    @Operation(summary = "兑换单列表（keyword 试订单号 / 商品名 / 兑换人姓名或手机号）")
    @SaCheckPermission(value = {PermissionCode.DONATE_ORDER, PermissionCode.DONATE_ORDER_AUDIT},
            mode = SaMode.OR, type = "admin")
    @GetMapping("/orders")
    public Result<PageResult<MallOrderVO>> list(
            PageQuery query,
            @Parameter(description = "状态筛选，不传=全部") @RequestParam(required = false) Integer status,
            @Parameter(description = "订单号 / 商品名 / 兑换人姓名或手机号") @RequestParam(required = false) String keyword) {
        return Result.ok(orderService.listForAdmin(query, status, keyword));
    }

    @Operation(summary = "兑换审核通过（生成取货码并快照自提点）")
    @SaCheckPermission(value = PermissionCode.DONATE_ORDER_AUDIT, type = "admin")
    @PostMapping("/orders/{id}/approve")
    public Result<Void> approve(@PathVariable Long id) {
        orderService.approve(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "兑换审核驳回（退分 + 还库存）")
    @SaCheckPermission(value = PermissionCode.DONATE_ORDER_AUDIT, type = "admin")
    @PostMapping("/orders/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody RejectReasonDTO dto) {
        orderService.reject(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    /**
     * 现场核销——<b>按取货码，不按订单 id</b>。
     *
     * <p>扫码枪扫出来的是码；做成 {@code /orders/{id}/verify} 会逼着前端先按码查一次 id，
     * 多一次往返，还把一个本可原子的动作拆成两步。</p>
     *
     * <p>返回被核销的那张单，供柜台当场核对该发什么。</p>
     */
    @Operation(summary = "现场核销取货码（CAS，一次性；返回该发什么）")
    @SaCheckPermission(value = PermissionCode.DONATE_VERIFY, type = "admin")
    @PostMapping("/orders/verify")
    public Result<MallOrderVO> verify(@Valid @RequestBody MallOrderVerifyDTO dto) {
        return Result.ok(orderService.verify(dto.getCode(), StpAdminUtil.getLoginIdAsLong()));
    }
}
