package com.hengde.enterprise.controller;

import com.hengde.auth.config.StpEnterpriseUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.dto.MallGoodsSaveDTO;
import com.hengde.donate.service.MallGoodsService;
import com.hengde.donate.service.MallOrderService;
import com.hengde.donate.service.MallVerifierService;
import com.hengde.donate.vo.MallGoodsVO;
import com.hengde.donate.vo.MallVerifierVO;
import com.hengde.donate.vo.SponsorOrderVO;
import com.hengde.enterprise.dto.EnterprisePointDTOs;
import com.hengde.enterprise.service.EnterprisePointService;
import com.hengde.enterprise.service.EnterpriseSponsorService;
import com.hengde.enterprise.vo.EnterprisePointVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业端-赞助商品 / 兑换单 / 核销员 / 积分（V4 爱心企业批·商品段）。企业 id 一律取登录态；这组接口要企业审核通过（api 的企业端闸门）。
 *
 * @author hengde
 */
@Tag(name = "企业端-赞助商品与积分")
@RestController
@RequestMapping("/e")
public class EnterpriseGoodsController {

    private MallGoodsService mallGoodsService;
    private MallOrderService mallOrderService;
    private MallVerifierService mallVerifierService;
    private EnterpriseSponsorService sponsorService;
    private EnterprisePointService pointService;

    @Autowired
    public void setMallGoodsService(MallGoodsService mallGoodsService) {
        this.mallGoodsService = mallGoodsService;
    }

    @Autowired
    public void setMallOrderService(MallOrderService mallOrderService) {
        this.mallOrderService = mallOrderService;
    }

    @Autowired
    public void setMallVerifierService(MallVerifierService mallVerifierService) {
        this.mallVerifierService = mallVerifierService;
    }

    @Autowired
    public void setSponsorService(EnterpriseSponsorService sponsorService) {
        this.sponsorService = sponsorService;
    }

    @Autowired
    public void setPointService(EnterprisePointService pointService) {
        this.pointService = pointService;
    }

    private static Long me() {
        return StpEnterpriseUtil.getLoginIdAsLong();
    }

    @Operation(summary = "我赞助的商品（含草稿 / 待审核 / 驳回原因；?status= 0草稿/1待审核/2已上架/3已停用/4已驳回）")
    @GetMapping("/donate/goods")
    public Result<PageResult<MallGoodsVO>> goods(PageQuery query, @RequestParam(required = false) Integer status) {
        return Result.ok(mallGoodsService.listForSponsor(me(), query, status));
    }

    @Operation(summary = "商品详情（只看得到自己的）")
    @GetMapping("/donate/goods/{id}")
    public Result<MallGoodsVO> goodsDetail(@PathVariable Long id) {
        return Result.ok(mallGoodsService.detailForSponsor(id, me()));
    }

    @Operation(summary = "新增赞助商品（落草稿；赞助方名称取企业名称，不能设「必须持卷」）")
    @PostMapping("/donate/goods")
    public Result<Long> createGoods(@Valid @RequestBody MallGoodsSaveDTO dto) {
        return Result.ok(sponsorService.createGoods(me(), dto));
    }

    @Operation(summary = "修改赞助商品（只能改自己的；改已过审的退回待审核，审核中不许改）")
    @PutMapping("/donate/goods/{id}")
    public Result<Void> updateGoods(@PathVariable Long id, @Valid @RequestBody MallGoodsSaveDTO dto) {
        mallGoodsService.updateForSponsor(id, me(), dto);
        return Result.ok();
    }

    @Operation(summary = "提交审核（草稿 / 驳回稿 → 待审核，审核在后台）")
    @PostMapping("/donate/goods/{id}/submit")
    public Result<Void> submitGoods(@PathVariable Long id) {
        mallGoodsService.submitForSponsor(id, me());
        return Result.ok();
    }

    @Operation(summary = "隐藏 / 显示（Row 8 F 商品隐藏；不触发重审）")
    @PutMapping("/donate/goods/{id}/hidden")
    public Result<Void> hideGoods(@PathVariable Long id, @Parameter(description = "1隐藏/0显示") @RequestParam int hidden) {
        mallGoodsService.hideForSponsor(id, me(), hidden);
        return Result.ok();
    }

    @Operation(summary = "删除赞助商品（历史兑换单有快照不受影响）")
    @DeleteMapping("/donate/goods/{id}")
    public Result<Void> deleteGoods(@PathVariable Long id) {
        mallGoodsService.deleteForSponsor(id, me());
        return Result.ok();
    }

    @Operation(summary = "我赞助商品的兑换单（没有取货码与收件信息，兑换人只留姓）")
    @GetMapping("/donate/orders")
    public Result<PageResult<SponsorOrderVO>> orders(PageQuery query, @RequestParam(required = false) Integer status) {
        return Result.ok(mallOrderService.listForSponsor(me(), query, status));
    }

    @Operation(summary = "我的核销员（只能核销本企业赞助的商品）")
    @GetMapping("/donate/verifiers")
    public Result<PageResult<MallVerifierVO>> verifiers(PageQuery query) {
        return Result.ok(mallVerifierService.listForEnterprise(me(), query));
    }

    @Operation(summary = "指派核销员（按已实名志愿者的手机号；一个志愿者同时只能是一处的核销员）")
    @PostMapping("/donate/verifiers")
    public Result<Long> assignVerifier(@Valid @RequestBody EnterprisePointDTOs.VerifierAssign dto) {
        return Result.ok(mallVerifierService.assignForEnterprise(me(), dto.getPhone(), dto.getRemark()));
    }

    @Operation(summary = "撤销核销员")
    @DeleteMapping("/donate/verifiers/{id}")
    public Result<Void> removeVerifier(@PathVariable Long id) {
        mallVerifierService.removeForEnterprise(id, me());
        return Result.ok();
    }

    @Operation(summary = "企业积分总览（余额 / 累计兑换入账 / 累计后台调整）")
    @GetMapping("/enterprise/points")
    public Result<EnterprisePointVOs.Summary> points() {
        return Result.ok(pointService.summary(me()));
    }

    @Operation(summary = "企业积分流水（?sourceType= 1兑换入账/2后台调整）")
    @GetMapping("/enterprise/points/records")
    public Result<PageResult<EnterprisePointVOs.Record>> pointRecords(PageQuery query, @RequestParam(required = false) Integer sourceType) {
        return Result.ok(pointService.records(me(), query, sourceType));
    }
}
