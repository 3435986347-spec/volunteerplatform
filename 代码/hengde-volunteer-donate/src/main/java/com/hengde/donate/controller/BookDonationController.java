package com.hengde.donate.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.constant.ExpressCompany;
import com.hengde.donate.dto.ReturnAddressDTO;
import com.hengde.donate.dto.ShipmentRegisterDTO;
import com.hengde.donate.service.BookCampaignService;
import com.hengde.donate.service.DonateItemService;
import com.hengde.donate.service.DonateMasterDataService;
import com.hengde.donate.service.DonateShipmentService;
import com.hengde.donate.service.DonateTrackService;
import com.hengde.donate.vo.DonateFlowVOs;
import com.hengde.donate.vo.DonateMasterVOs;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-公益捐书（{@code /v/donate}，V3 捐书批）。
 *
 * <p>仅需登录；志愿者 id 一律取自登录态。登记寄出另要求已实名（服务层判定）。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-公益捐书")
@RestController
@RequestMapping("/v/donate")
public class BookDonationController {

    private BookCampaignService campaignService;
    private DonateShipmentService shipmentService;
    private DonateItemService itemService;
    private DonateMasterDataService masterDataService;
    private DonateTrackService trackService;

    @Autowired
    public void setCampaignService(BookCampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @Autowired
    public void setShipmentService(DonateShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @Autowired
    public void setItemService(DonateItemService itemService) {
        this.itemService = itemService;
    }

    @Autowired
    public void setMasterDataService(DonateMasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    @Autowired
    public void setTrackService(DonateTrackService trackService) {
        this.trackService = trackService;
    }

    @Operation(summary = "捐书活动列表（含本次活动数据）")
    @GetMapping("/book-campaigns")
    public Result<PageResult<DonateFlowVOs.Campaign>> campaigns(PageQuery query) {
        return Result.ok(campaignService.listForVolunteer(query));
    }

    @Operation(summary = "捐书活动详情（含收件信息：收件人=我的姓名，地址=预留地址+我的姓名）")
    @GetMapping("/book-campaigns/{id}")
    public Result<DonateFlowVOs.Campaign> campaign(@PathVariable Long id) {
        return Result.ok(campaignService.detailForVolunteer(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "报名并登记寄出（录入物资 + 快递公司 + 单号，一次提交）")
    @PostMapping("/book-campaigns/{id}/shipments")
    public Result<DonateFlowVOs.Shipment> register(@PathVariable Long id, @Valid @RequestBody ShipmentRegisterDTO dto) {
        return Result.ok(shipmentService.register(StpUtil.getLoginIdAsLong(), id, dto));
    }

    @Operation(summary = "我的运单（含物资明细与流转轨迹，Row 38 运单管理）")
    @GetMapping("/shipments/mine")
    public Result<PageResult<DonateFlowVOs.Shipment>> myShipments(PageQuery query) {
        return Result.ok(shipmentService.listMine(StpUtil.getLoginIdAsLong(), query));
    }

    @Operation(summary = "我的运单详情")
    @GetMapping("/shipments/{id}")
    public Result<DonateFlowVOs.Shipment> myShipment(@PathVariable Long id) {
        return Result.ok(shipmentService.detailMine(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "取消寄送（仅机构确认到货之前）")
    @DeleteMapping("/shipments/{id}")
    public Result<Void> cancel(@PathVariable Long id) {
        shipmentService.cancel(id, StpUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "提交不合格物资的退回收件信息")
    @PostMapping("/shipments/{id}/return-address")
    public Result<Void> returnAddress(@PathVariable Long id, @Valid @RequestBody ReturnAddressDTO dto) {
        shipmentService.submitReturnAddress(id, StpUtil.getLoginIdAsLong(), dto);
        return Result.ok();
    }

    @Operation(summary = "运单物流轨迹（快递100 快照；未开通时 available=false）")
    @GetMapping("/shipments/{id}/track")
    public Result<DonateFlowVOs.Track> track(@PathVariable Long id) {
        return Result.ok(trackService.trackForDonor(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "我的捐书记录（Row 38：编号、名称、编码、受捐学校、审核状态、物资类型、借阅次数）")
    @GetMapping("/items/mine")
    public Result<PageResult<DonateFlowVOs.Item>> myItems(PageQuery query) {
        return Result.ok(itemService.listMine(StpUtil.getLoginIdAsLong(), query));
    }

    @Operation(summary = "按商品条码查条码库（查不到返回 null，手填即可）")
    @GetMapping("/barcode-catalog/{barcode}")
    public Result<DonateMasterVOs.Catalog> lookup(@PathVariable String barcode) {
        return Result.ok(masterDataService.lookup(barcode));
    }

    @Operation(summary = "可选的快递公司")
    @GetMapping("/express-companies")
    public Result<List<DonateFlowVOs.Express>> expressCompanies() {
        return Result.ok(ExpressCompany.all().stream().map(c -> {
            DonateFlowVOs.Express e = new DonateFlowVOs.Express();
            e.setCode(c.getCode());
            e.setLabel(c.getLabel());
            e.setTrackable(c.trackable());
            return e;
        }).toList());
    }
}
