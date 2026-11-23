package com.hengde.system.controller;

import com.hengde.common.result.Result;
import com.hengde.system.service.FileVaultService;
import com.hengde.system.vo.SystemVOs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-内置文件（Row 19「内置文件由后台文件管理板块开放文件至该板块，该板块可以设置文件开放时间、开放/关闭下载」）。
 *
 * <p><b>开放窗口按时间现算</b>，没到点、过了点的一律不出现；<b>关掉下载的只给名字不给地址</b>——
 * 只在前端隐藏按钮的话，地址已经躺在响应里了。</p>
 *
 * <p>与 {@code GET /v/publicity/files}（后台直接传的公示文件）是两份来源，小程序「文件下载」板块把两处合着显示。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-内置文件")
@RestController
@RequestMapping("/v/system")
public class VolunteerSystemFileController {

    private FileVaultService vaultService;

    @Autowired
    public void setVaultService(FileVaultService vaultService) {
        this.vaultService = vaultService;
    }

    @Operation(summary = "此刻开放的内置文件（关掉下载的不下发地址）")
    @GetMapping("/files")
    public Result<List<SystemVOs.OpenFile>> files() {
        return Result.ok(vaultService.openFiles());
    }
}
