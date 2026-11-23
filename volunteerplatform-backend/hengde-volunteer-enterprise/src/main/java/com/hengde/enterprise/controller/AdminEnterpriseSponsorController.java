package com.hengde.enterprise.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.dto.MallGoodsSaveDTO;
import com.hengde.donate.service.MallGoodsService;
import com.hengde.donate.vo.MallGoodsVO;
import com.hengde.enterprise.constant.PermissionCode;
import com.hengde.enterprise.dto.EnterprisePointDTOs;
import com.hengde.enterprise.service.EnterprisePointService;
import com.hengde.enterprise.service.EnterpriseSponsorService;
import com.hengde.enterprise.vo.EnterprisePointVOs;
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
 * 管理端-爱心企业的赞助商品与积分（V4 爱心企业批·商品段）。
 *
 * <p>代发布挂 {@code donate:goods}（与后台发商品同一个点——它就是在发商品），积分挂 {@code enterprise:points}。审核仍在 {@code /a/donate/goods}。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-爱心企业赞助与积分")
@RestController
@RequestMapping("/a/enterprise")
public class AdminEnterpriseSponsorController {

    private EnterpriseSponsorService sponsorService;
    private MallGoodsService mallGoodsService;
    private EnterprisePointService pointService;

    @Autowired
    public void setSponsorService(EnterpriseSponsorService sponsorService) {
        this.sponsorService = sponsorService;
    }

    @Autowired
    public void setMallGoodsService(MallGoodsService mallGoodsService) {
        this.mallGoodsService = mallGoodsService;
    }

    @Autowired
    public void setPointService(EnterprisePointService pointService) {
        this.pointService = pointService;
    }

    @Operation(summary = "以企业名义代发布积分商品（落草稿；企业须正常）")
    @SaCheckPermission(value = com.hengde.donate.constant.PermissionCode.DONATE_GOODS, type = "admin")
    @PostMapping("/enterprises/{id}/goods")
    public Result<Long> createGoods(@PathVariable Long id, @Valid @RequestBody MallGoodsSaveDTO dto) {
        return Result.ok(sponsorService.createGoods(id, dto));
    }

    @Operation(summary = "某企业赞助的商品（含草稿 / 待审核）")
    @SaCheckPermission(value = {com.hengde.donate.constant.PermissionCode.DONATE_GOODS, PermissionCode.ENTERPRISE_MANAGE},
            mode = SaMode.OR, type = "admin")
    @GetMapping("/enterprises/{id}/goods")
    public Result<PageResult<MallGoodsVO>> goods(@PathVariable Long id, PageQuery query, @RequestParam(required = false) Integer status) {
        return Result.ok(mallGoodsService.listForSponsor(id, query, status));
    }

    @Operation(summary = "企业积分总览")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_POINTS, type = "admin")
    @GetMapping("/enterprises/{id}/points")
    public Result<EnterprisePointVOs.Summary> points(@PathVariable Long id) {
        return Result.ok(pointService.summary(id));
    }

    @Operation(summary = "企业积分流水")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_POINTS, type = "admin")
    @GetMapping("/enterprises/{id}/points/records")
    public Result<PageResult<EnterprisePointVOs.Record>> pointRecords(@PathVariable Long id, PageQuery query,
            @RequestParam(required = false) Integer sourceType) {
        return Result.ok(pointService.records(id, query, sourceType));
    }

    @Operation(summary = "调整企业积分（兑换企业权益时扣减；带幂等键，扣减不许扣成负数）")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_POINTS, type = "admin")
    @PostMapping("/enterprises/{id}/points/adjust")
    public Result<EnterprisePointVOs.Summary> adjust(@PathVariable Long id, @Valid @RequestBody EnterprisePointDTOs.Adjust dto) {
        return Result.ok(pointService.adjust(id, dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "补记兑换入账（定时任务回看窗口之外漏掉的；since 必填，不许是将来）")
    @SaCheckPermission(value = PermissionCode.ENTERPRISE_POINTS, type = "admin")
    @PostMapping("/points/reconcile")
    public Result<Integer> reconcile(@Parameter(description = "领取时间不早于它的兑换单") @RequestParam
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime since) {
        if (since.isAfter(LocalDateTime.now())) {
            throw new BusinessException("起始时间不能是将来");
        }
        return Result.ok(pointService.creditPickedOrders(since));
    }
}
