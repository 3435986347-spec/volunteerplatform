package com.hengde.donate.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.excel.ExcelUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.donate.constant.PermissionCode;
import com.hengde.donate.dto.WishDTOs;
import com.hengde.donate.service.WishService;
import com.hengde.donate.vo.WishVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 管理端-圆梦微心愿（{@code /a/donate/wishes}，V3 微心愿批）。全部挂 {@code donate:wish}。
 *
 * <p>认领人寄来的包裹之后走捐书那一套通用接口：扫码到货、核对、生成专属码（{@code /a/donate/shipments/…}、
 * {@code /a/donate/items/…}），与捐书的包裹同一个扫码页处理；这里只管心愿本身与「实现」这一步。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-圆梦微心愿")
@RestController
@RequestMapping("/a/donate")
public class AdminWishController {

    private WishService wishService;

    @Autowired
    public void setWishService(WishService wishService) {
        this.wishService = wishService;
    }

    @Operation(summary = "心愿列表（搜索框：标题 / 编号 / 上报单位；状态下拉 0待认领/1已认领/2已实现/3已下架）")
    @SaCheckPermission(value = PermissionCode.DONATE_WISH, type = "admin")
    @GetMapping("/wishes")
    public Result<PageResult<WishVOs.Wish>> list(@RequestParam(required = false) Integer status,
                                                 @RequestParam(required = false) String keyword,
                                                 PageQuery query) {
        return Result.ok(wishService.listForAdmin(query, status, keyword));
    }

    @Operation(summary = "单独上传一个心愿（编号留空由系统生成）")
    @SaCheckPermission(value = PermissionCode.DONATE_WISH, type = "admin")
    @PostMapping("/wishes")
    public Result<Long> create(@Valid @RequestBody WishDTOs.Save dto) {
        return Result.ok(wishService.create(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "批量导入心愿（xlsx，全成或全不成；失败时逐行给出原因）")
    @SaCheckPermission(value = PermissionCode.DONATE_WISH, type = "admin")
    @PostMapping(value = "/wishes/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<WishVOs.ImportResult> importWishes(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要导入的 Excel 文件");
        }
        List<WishDTOs.ImportRow> rows;
        try (InputStream in = file.getInputStream()) {
            rows = ExcelUtil.read(in, WishDTOs.ImportRow.class);
        } catch (IOException e) {
            throw new BusinessException("文件读取失败，请重新上传");
        }
        return Result.ok(wishService.importRows(rows, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "下载导入模板（表头即列名）")
    @SaCheckPermission(value = PermissionCode.DONATE_WISH, type = "admin")
    @GetMapping("/wishes/import-template")
    public void importTemplate(HttpServletResponse response) {
        ExcelUtil.export(response, "微心愿导入模板", "心愿", WishDTOs.ImportRow.class, List.of());
    }

    @Operation(summary = "批量下载心愿（含未成年人资料）")
    @SaCheckPermission(value = PermissionCode.DONATE_WISH, type = "admin")
    @GetMapping("/wishes/export")
    public void export(@RequestParam(required = false) Integer status,
                       @RequestParam(required = false) String keyword,
                       HttpServletResponse response) {
        List<WishVOs.ExportRow> rows = wishService.exportRows(status, keyword);
        ExcelUtil.export(response, "微心愿", "心愿", WishVOs.ExportRow.class, rows);
    }

    @Operation(summary = "心愿详情：资料全文 + 历次认领（认领人、寄来的包裹、物资与轨迹）")
    @SaCheckPermission(value = PermissionCode.DONATE_WISH, type = "admin")
    @GetMapping("/wishes/{id}")
    public Result<WishVOs.AdminDetail> detail(@PathVariable Long id) {
        return Result.ok(wishService.detailForAdmin(id));
    }

    @Operation(summary = "修改心愿资料（仅待认领 / 已下架；编号留空表示不改）")
    @SaCheckPermission(value = PermissionCode.DONATE_WISH, type = "admin")
    @PutMapping("/wishes/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody WishDTOs.Save dto) {
        wishService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "下架（仅待认领）")
    @SaCheckPermission(value = PermissionCode.DONATE_WISH, type = "admin")
    @PostMapping("/wishes/{id}/take-down")
    public Result<Void> takeDown(@PathVariable Long id) {
        wishService.takeDown(id);
        return Result.ok();
    }

    @Operation(summary = "重新上架（回到心愿池）")
    @SaCheckPermission(value = PermissionCode.DONATE_WISH, type = "admin")
    @PostMapping("/wishes/{id}/restore")
    public Result<Void> restore(@PathVariable Long id) {
        wishService.restore(id);
        return Result.ok();
    }

    @Operation(summary = "后台取消认领（原因必填，认领人收到站内提示；物资已在流转中则不可撤）")
    @SaCheckPermission(value = PermissionCode.DONATE_WISH, type = "admin")
    @PostMapping("/wishes/{id}/revoke-claim")
    public Result<Void> revokeClaim(@PathVariable Long id, @Valid @RequestBody WishDTOs.Revoke dto) {
        wishService.revokeClaim(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "心愿实现：合格物资发放 + 上传发放图片（1~9 张），认领人收到站内提示")
    @SaCheckPermission(value = PermissionCode.DONATE_WISH, type = "admin")
    @PostMapping("/wishes/{id}/realize")
    public Result<Void> realize(@PathVariable Long id, @Valid @RequestBody WishDTOs.Realize dto) {
        wishService.realize(id, dto.getImages(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
