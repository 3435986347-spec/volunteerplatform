package com.hengde.honor.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.result.Result;
import com.hengde.honor.constant.PermissionCode;
import com.hengde.honor.dto.CertificateTemplateSaveDTO;
import com.hengde.honor.service.CertificateTemplateService;
import com.hengde.honor.vo.CertificateTemplateVO;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 管理端-证书电子样本（{@code /a/honor/certificate-templates}）。
 *
 * <p>需求原文 xlsx Row 36 F 列第 ③ 项：「<b>设置某个活动的电子样本</b>」——
 * 故作用域可以是 {@code activity:{id}}（传 activityId）或全局默认（不传）。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-证书电子样本")
@RestController
@RequestMapping("/a/honor")
public class AdminCertificateTemplateController {

    private CertificateTemplateService templateService;

    @Autowired
    public void setTemplateService(CertificateTemplateService templateService) {
        this.templateService = templateService;
    }

    @Operation(summary = "电子样本列表")
    @SaCheckPermission(value = PermissionCode.HONOR_CERTIFICATE_TEMPLATE, type = "admin")
    @GetMapping("/certificate-templates")
    public Result<List<CertificateTemplateVO>> list() {
        return Result.ok(templateService.list());
    }

    /**
     * <p>样本必须存成<b>私有</b>对象：它要被服务端回读当底图，且不该对全网可取。
     * 既有的 {@code /a/files/upload} 会打公共读 ACL 并返回 URL，<b>产不出这里要的 key</b>。</p>
     */
    @Operation(summary = "上传样本 PDF（私有对象），返回可填进 fileKey 的对象 key")
    @SaCheckPermission(value = PermissionCode.HONOR_CERTIFICATE_TEMPLATE, type = "admin")
    @PostMapping("/certificate-templates/file")
    public Result<String> uploadFile(@RequestPart("file") MultipartFile file) {
        return Result.ok(templateService.uploadTemplateFile(file));
    }

    @Operation(summary = "新增电子样本（传 activityId=按活动，不传=全局默认）")
    @SaCheckPermission(value = PermissionCode.HONOR_CERTIFICATE_TEMPLATE, type = "admin")
    @PostMapping("/certificate-templates")
    public Result<Long> create(@RequestBody @Valid CertificateTemplateSaveDTO dto) {
        return Result.ok(templateService.create(dto, StpAdminUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "修改电子样本（作用域不可改，要换活动请新建）")
    @SaCheckPermission(value = PermissionCode.HONOR_CERTIFICATE_TEMPLATE, type = "admin")
    @PutMapping("/certificate-templates/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid CertificateTemplateSaveDTO dto) {
        templateService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除电子样本")
    @SaCheckPermission(value = PermissionCode.HONOR_CERTIFICATE_TEMPLATE, type = "admin")
    @DeleteMapping("/certificate-templates/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return Result.ok();
    }
}
