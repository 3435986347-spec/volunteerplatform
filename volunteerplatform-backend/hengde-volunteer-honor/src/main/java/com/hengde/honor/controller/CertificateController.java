package com.hengde.honor.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.honor.service.CertificateService;
import com.hengde.honor.vo.CertificateVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @Operation(summary = "我的证书列表（分页；软删的不返回）。category 可选：activity 志愿活动 / pair 助学助困 / wish 微心愿 / "
            + "book 公益捐书 / crowdfund 项目众筹（原型 P83 的页签）")
    @GetMapping("/certificates")
    public Result<PageResult<CertificateVO>> myCertificates(PageQuery query,
            @RequestParam(required = false) String category) {
        return Result.ok(certificateService.myCertificates(StpUtil.getLoginIdAsLong(), query, category));
    }

    @Operation(summary = "按来源键取本人的捐赠证书（如捐物记录上的「证书」按钮传 cf-goods:运单id、心愿详情传 wish:认领id）；"
            + "还没有证书（未审核 / 未实现 / 未到账）或不是本人的返回 null")
    @GetMapping("/certificates/by-ref")
    public Result<CertificateVO> byRef(@RequestParam String bizRef) {
        return Result.ok(certificateService.findMineByRef(StpUtil.getLoginIdAsLong(), bizRef));
    }

    /**
     * <p>返回的是<b>短期签名 URL</b>，不是文件流：证书存在私有对象存储里，
     * 由前端拿这个链接直接向对象存储取，服务端不做流量中转。
     * 链接在有效期内等同凭证，故有效期很短（默认 120 秒）且不落库、不进日志。</p>
     */
    @Operation(summary = "下载：文件缺失时懒渲染并回填，返回短期签名 URL，并累加下载次数")
    @GetMapping("/certificates/{id}/file")
    public Result<String> file(@PathVariable Long id) {
        return Result.ok(certificateService.downloadUrlForVolunteer(id, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 协会 2026-08-11：「先预览，再由用户决定是否下载」——预览与下载拿到的都是短期签名 URL，
     * 只有下载（上面的 {@code /file}）计下载次数。
     */
    @Operation(summary = "预览：文件缺失时懒渲染并回填，返回短期签名 URL，不计下载次数")
    @GetMapping("/certificates/{id}/preview")
    public Result<String> preview(@PathVariable Long id) {
        return Result.ok(certificateService.previewUrlForVolunteer(id, StpUtil.getLoginIdAsLong()));
    }
}
