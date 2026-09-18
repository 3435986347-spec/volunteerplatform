package com.hengde.donate.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.excel.ExcelUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.constant.PermissionCode;
import com.hengde.donate.dto.BarcodeCatalogSaveDTO;
import com.hengde.donate.dto.BookCampaignSaveDTO;
import com.hengde.donate.dto.BoxDTOs;
import com.hengde.donate.dto.DonateItemInputDTO;
import com.hengde.donate.dto.DonateItemQuery;
import com.hengde.donate.dto.ExpressDTO;
import com.hengde.donate.dto.RecipientOrgSaveDTO;
import com.hengde.donate.dto.ShipmentCheckDTO;
import com.hengde.donate.service.BookCampaignService;
import com.hengde.donate.service.DonateBoxService;
import com.hengde.donate.service.DonateItemService;
import com.hengde.donate.service.DonateLogisticsPushService;
import com.hengde.donate.service.DonateMasterDataService;
import com.hengde.donate.service.DonateScanService;
import com.hengde.donate.service.DonateShipmentService;
import com.hengde.donate.service.DonateTrackService;
import com.hengde.donate.vo.DonateFlowVOs;
import com.hengde.donate.vo.DonateItemExportRow;
import com.hengde.donate.vo.DonateItemRow;
import com.hengde.donate.vo.DonateMasterVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
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

import java.util.List;

/**
 * 管理端-公益捐书与物资流转（{@code /a/donate}，V3 捐书批）。
 *
 * <p>除导出外全部挂 {@code donate:item}；导出单挂 {@code donate:item-export}（理由见 {@link PermissionCode}）。</p>
 *
 * <p><b>扫码动作一次一个</b>：到货 / 生成专属码 / 装箱 / 出箱 / 送达都是「扫一个码 = 一次请求」，
 * 核对是唯一带列表的动作（扫一个包裹、判里面每一件）。扫码页先调 {@code GET /scan?code=}
 * 知道扫到的是什么，再调对应的动作接口。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-公益捐书与物资流转")
@RestController
@RequestMapping("/a/donate")
public class AdminBookDonationController {

    private BookCampaignService campaignService;
    private DonateMasterDataService masterDataService;
    private DonateShipmentService shipmentService;
    private DonateItemService itemService;
    private DonateBoxService boxService;
    private DonateScanService scanService;
    private DonateTrackService trackService;
    private DonateLogisticsPushService pushService;

    @Autowired
    public void setPushService(DonateLogisticsPushService pushService) {
        this.pushService = pushService;
    }

    @Autowired
    public void setCampaignService(BookCampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @Autowired
    public void setMasterDataService(DonateMasterDataService masterDataService) {
        this.masterDataService = masterDataService;
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
    public void setBoxService(DonateBoxService boxService) {
        this.boxService = boxService;
    }

    @Autowired
    public void setScanService(DonateScanService scanService) {
        this.scanService = scanService;
    }

    @Autowired
    public void setTrackService(DonateTrackService trackService) {
        this.trackService = trackService;
    }

    // ================= 捐书活动 =================

    @Operation(summary = "捐书活动列表（含本次活动数据）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @GetMapping("/book-campaigns")
    public Result<PageResult<DonateFlowVOs.Campaign>> campaigns(
            PageQuery query, @RequestParam(required = false) String keyword,
            @Parameter(description = "0草稿/1已发布/2已结束") @RequestParam(required = false) Integer status) {
        return Result.ok(campaignService.listForAdmin(query, keyword, status));
    }

    @Operation(summary = "捐书活动详情")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @GetMapping("/book-campaigns/{id}")
    public Result<DonateFlowVOs.Campaign> campaign(@PathVariable Long id) {
        return Result.ok(campaignService.detailForAdmin(id));
    }

    @Operation(summary = "新建捐书活动（落草稿）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/book-campaigns")
    public Result<Long> createCampaign(@Valid @RequestBody BookCampaignSaveDTO dto) {
        return Result.ok(campaignService.create(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "修改捐书活动")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PutMapping("/book-campaigns/{id}")
    public Result<Void> updateCampaign(@PathVariable Long id, @Valid @RequestBody BookCampaignSaveDTO dto) {
        campaignService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "发布（须先填收件电话与地址）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/book-campaigns/{id}/publish")
    public Result<Void> publish(@PathVariable Long id) {
        campaignService.publish(id);
        return Result.ok();
    }

    @Operation(summary = "手动结束")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/book-campaigns/{id}/end")
    public Result<Void> end(@PathVariable Long id) {
        campaignService.end(id);
        return Result.ok();
    }

    @Operation(summary = "删除（仅草稿）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @DeleteMapping("/book-campaigns/{id}")
    public Result<Void> deleteCampaign(@PathVariable Long id) {
        campaignService.delete(id);
        return Result.ok();
    }

    // ================= 受赠单位 =================

    @Operation(summary = "受赠单位列表")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @GetMapping("/recipient-orgs")
    public Result<PageResult<DonateMasterVOs.RecipientOrg>> orgs(
            PageQuery query, @RequestParam(required = false) String keyword,
            @Parameter(description = "1启用/0停用") @RequestParam(required = false) Integer status) {
        return Result.ok(masterDataService.listOrgs(query, keyword, status));
    }

    @Operation(summary = "新建受赠单位")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/recipient-orgs")
    public Result<Long> createOrg(@Valid @RequestBody RecipientOrgSaveDTO dto) {
        return Result.ok(masterDataService.createOrg(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "修改受赠单位")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PutMapping("/recipient-orgs/{id}")
    public Result<Void> updateOrg(@PathVariable Long id, @Valid @RequestBody RecipientOrgSaveDTO dto) {
        masterDataService.updateOrg(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除受赠单位（已送达记录存的是名称快照，不受影响）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @DeleteMapping("/recipient-orgs/{id}")
    public Result<Void> deleteOrg(@PathVariable Long id) {
        masterDataService.deleteOrg(id);
        return Result.ok();
    }

    // ================= 商品条码库 =================

    @Operation(summary = "商品条码库列表（keyword 精确匹配条码或模糊匹配名称）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @GetMapping("/barcode-catalog")
    public Result<PageResult<DonateMasterVOs.Catalog>> catalog(PageQuery query,
                                                               @RequestParam(required = false) String keyword) {
        return Result.ok(masterDataService.listCatalog(query, keyword));
    }

    @Operation(summary = "新增条码库条目")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/barcode-catalog")
    public Result<Long> createCatalog(@Valid @RequestBody BarcodeCatalogSaveDTO dto) {
        return Result.ok(masterDataService.createCatalog(dto));
    }

    @Operation(summary = "修改条码库条目")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PutMapping("/barcode-catalog/{id}")
    public Result<Void> updateCatalog(@PathVariable Long id, @Valid @RequestBody BarcodeCatalogSaveDTO dto) {
        masterDataService.updateCatalog(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除条码库条目")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @DeleteMapping("/barcode-catalog/{id}")
    public Result<Void> deleteCatalog(@PathVariable Long id) {
        masterDataService.deleteCatalog(id);
        return Result.ok();
    }

    // ================= 运单 =================

    @Operation(summary = "运单列表（bizType 不传＝捐书活动；2 微心愿 / 3 众筹捐物，bizId 为对应的认领 / 众筹项目 id）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @GetMapping("/shipments")
    public Result<PageResult<DonateFlowVOs.Shipment>> shipments(
            PageQuery query,
            @RequestParam(required = false) Long campaignId,
            @Parameter(description = "来源 1捐书活动（默认）/2微心愿/3众筹捐物") @RequestParam(required = false) Integer bizType,
            @Parameter(description = "来源 id；与 campaignId 同时给时以它为准") @RequestParam(required = false) Long bizId,
            @Parameter(description = "1已寄出/2已到货/3已核对/4已取消") @RequestParam(required = false) Integer status,
            @Parameter(description = "0无需退回/1待提交收件信息/2待寄回/3已寄回") @RequestParam(required = false) Integer returnStatus,
            @RequestParam(required = false) String expressNo,
            @RequestParam(required = false) String donorName) {
        return Result.ok(shipmentService.listForAdmin(query, bizType, bizId != null ? bizId : campaignId, status,
                returnStatus, expressNo, donorName));
    }

    @Operation(summary = "运单详情（含物资、轨迹、退回收件信息明文）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @GetMapping("/shipments/{id}")
    public Result<DonateFlowVOs.Shipment> shipment(@PathVariable Long id) {
        return Result.ok(shipmentService.detailForAdmin(id));
    }

    @Operation(summary = "扫码确认到货（Row 17 第 5 步）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/shipments/{id}/arrive")
    public Result<Void> arrive(@PathVariable Long id) {
        shipmentService.arrive(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "核对捐赠单据（逐件判定，须覆盖全部待核对物资，Row 17 第 6 步）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/shipments/{id}/check")
    public Result<Void> check(@PathVariable Long id, @Valid @RequestBody ShipmentCheckDTO dto) {
        shipmentService.check(id, dto, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "登记不合格物资寄回（快递公司 + 单号；按运单退）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/shipments/{id}/return")
    public Result<Void> returnShip(@PathVariable Long id, @Valid @RequestBody ExpressDTO dto) {
        shipmentService.returnShip(id, dto, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "单独添加物资（仅已到货 / 已核对的包裹）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/shipments/{id}/items")
    public Result<Long> addItem(@PathVariable Long id, @Valid @RequestBody DonateItemInputDTO dto) {
        return Result.ok(shipmentService.addItem(id, dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "运单物流轨迹（refresh=true 强制重查，仍受终态约束）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @GetMapping("/shipments/{id}/track")
    public Result<DonateFlowVOs.Track> track(@PathVariable Long id,
                                             @RequestParam(required = false, defaultValue = "false") boolean refresh) {
        return Result.ok(trackService.trackForAdmin(id, refresh));
    }

    @Operation(summary = "重新订阅物流推送（仅「被中止 / 已放弃」且仍在途的运单；由定时任务下一轮发起，订阅是付费项）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/shipments/{id}/subscribe")
    public Result<Void> resubscribe(@PathVariable Long id) {
        pushService.resubscribe(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    // ================= 物资 =================

    @Operation(summary = "单独减少物资（已装箱 / 已送达 / 已寄回的不能删）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @DeleteMapping("/items/{id}")
    public Result<Void> removeItem(@PathVariable Long id) {
        shipmentService.removeItem(id, StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "生成物品专属码并返回标签（幂等：已有码返回原码供重印）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/items/{id}/barcode")
    public Result<DonateFlowVOs.ItemLabel> barcode(@PathVariable Long id) {
        return Result.ok(itemService.generateCode(id, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "物资 10 维搜索（箱码 / 专属码 / 物品条码 / 捐赠人名字·电话·单位 / 名称 / 类型 / 快递单号 / 进度）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @GetMapping("/items")
    public Result<PageResult<DonateItemRow>> items(DonateItemQuery q, PageQuery page) {
        return Result.ok(itemService.search(q, page));
    }

    @Operation(summary = "物资批量导出（每物资一行，Excel；上限 20000 行）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM_EXPORT, type = "admin")
    @GetMapping("/items/export")
    public void export(DonateItemQuery q, HttpServletResponse response) {
        List<DonateItemExportRow> rows = itemService.exportRows(q);
        ExcelUtil.export(response, "捐赠物资明细", "物资", DonateItemExportRow.class, rows);
    }

    // ================= 箱子 =================

    @Operation(summary = "新建箱子（生成箱码与条码图）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/boxes")
    public Result<DonateFlowVOs.Box> createBox(@Valid @RequestBody BoxDTOs.Create dto) {
        return Result.ok(boxService.create(dto.getCampaignId(), StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "箱子列表")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @GetMapping("/boxes")
    public Result<PageResult<DonateFlowVOs.Box>> boxes(
            PageQuery query, @RequestParam(required = false) Long campaignId,
            @Parameter(description = "0装箱中/1已送达") @RequestParam(required = false) Integer status) {
        return Result.ok(boxService.list(query, campaignId, status));
    }

    @Operation(summary = "箱子详情（含箱码条码图与箱内物资）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @GetMapping("/boxes/{id}")
    public Result<DonateFlowVOs.Box> box(@PathVariable Long id) {
        return Result.ok(boxService.detail(id));
    }

    @Operation(summary = "扫物品专属码装箱（一次一件）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/boxes/{id}/pack")
    public Result<DonateFlowVOs.Item> pack(@PathVariable Long id, @Valid @RequestBody BoxDTOs.Scan dto) {
        return Result.ok(boxService.pack(id, dto.getCode(), StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "扫物品专属码出箱（仅装箱中的箱子）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/boxes/{id}/unpack")
    public Result<DonateFlowVOs.Item> unpack(@PathVariable Long id, @Valid @RequestBody BoxDTOs.Scan dto) {
        return Result.ok(boxService.unpack(id, dto.getCode(), StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "送达受赠单位（Row 17 第 10 步；空箱不能送达）")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @PostMapping("/boxes/{id}/deliver")
    public Result<DonateFlowVOs.Box> deliver(@PathVariable Long id, @Valid @RequestBody BoxDTOs.Deliver dto) {
        return Result.ok(boxService.deliver(id, dto.getRecipientOrgId(), StpAdminUtil.getLoginIdAsLong()));
    }

    // ================= 扫码识别 =================

    @Operation(summary = "扫码识别：物品专属码 / 箱码 / 快递单号 / 商品条码")
    @SaCheckPermission(value = PermissionCode.DONATE_ITEM, type = "admin")
    @GetMapping("/scan")
    public Result<DonateFlowVOs.ScanResult> scan(@RequestParam String code) {
        return Result.ok(scanService.resolve(code));
    }
}
