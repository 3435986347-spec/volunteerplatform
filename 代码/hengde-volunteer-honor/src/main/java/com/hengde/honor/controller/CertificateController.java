package com.hengde.honor.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.result.Result;
import com.hengde.honor.service.CertificateService;
import com.hengde.honor.vo.CertificateVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 志愿者端-我的证书（{@code /v/honor/certificates}）。
 *
 * <p>需求原文 xlsx Row 36 C：「参加完活动后，自动生成一个盖章的电子证书……<b>电子证书预览和下载</b>」；
 * 原型 P83「我的 → 协会证书」列表 + 「下载PDF」按钮。</p>
 *
 * <p><b>没有「申请生成」这个动作</b>——原文是「参加完自动生成」，
 * 把它改成「申请后才有」属未经产品决策改动用户流程。故本控制器只有列表与下载。</p>
 *
 * <p><b>志愿者 id 一律取自登录态、不接受入参</b>，避免越权查他人证书。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-我的证书")
@RestController
@RequestMapping("/v/honor")
public class CertificateController {

    private CertificateService certificateService;

    @Autowired
    public void setCertificateService(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    @Operation(summary = "我的证书列表（软删的不返回）")
    @GetMapping("/certificates")
    public Result<List<CertificateVO>> myCertificates() {
        return Result.ok(certificateService.myCertificates(StpUtil.getLoginIdAsLong()));
    }

    /**
     * <p>返回的是<b>短期签名 URL</b>，不是文件流：证书存在私有对象存储里，
     * 由前端拿这个链接直接向对象存储取，服务端不做流量中转。
     * 链接在有效期内等同凭证，故有效期很短（默认 120 秒）且不落库、不进日志。</p>
     */
    @Operation(summary = "预览/下载：文件缺失时懒渲染并回填，返回短期签名 URL，并累加下载次数")
    @GetMapping("/certificates/{id}/file")
    public Result<String> file(@PathVariable Long id) {
        return Result.ok(certificateService.downloadUrlForVolunteer(id, StpUtil.getLoginIdAsLong()));
    }
}
