package com.hengde.honor.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.page.PageQuery;
import com.hengde.common.page.PageResult;
import com.hengde.common.result.Result;
import com.hengde.honor.constant.PermissionCode;
import com.hengde.honor.dto.CertificateDeleteDTO;
import com.hengde.honor.job.CertificateReconcileJob;
import com.hengde.honor.service.CertificateService;
import com.hengde.honor.vo.CertificateBatchUploadVO;
import com.hengde.honor.vo.CertificateReconcileVO;
import com.hengde.honor.vo.CertificateVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端-证书（{@code /a/honor/certificates}）。
 *
 * <p>对应 xlsx Row 36 F 列的后台能力：批量上传 PDF 证书、删除指定某人证书。
 * （「设置某个活动的电子样本」在 {@link AdminCertificateTemplateController}；
 * 「批量设置可申请纸质证书时间」随纸质路径整体冻结，不在本批。）</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-证书")
@RestController
@RequestMapping("/a/honor")
public class AdminCertificateController {

    private CertificateService certificateService;
    private CertificateReconcileJob reconcileJob;

    @Autowired
    public void setCertificateService(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    @Autowired
    public void setReconcileJob(CertificateReconcileJob reconcileJob) {
        this.reconcileJob = reconcileJob;
    }

    @Operation(summary = "证书汇总查询（可按志愿者/活动筛选；可含已软删，供撤销删除）")
    @SaCheckPermission(value = PermissionCode.HONOR_CERTIFICATE, type = "admin")
    @GetMapping("/certificates")
    public Result<PageResult<CertificateVO>> list(PageQuery query,
                                                  @Parameter(description = "志愿者 id") @RequestParam(required = false) Long volunteerId,
                                                  @Parameter(description = "活动 id") @RequestParam(required = false) Long activityId,
                                                  @Parameter(description = "是否包含已软删的证书，缺省 false")
                                                  @RequestParam(required = false, defaultValue = "false") boolean includeDeleted) {
        return Result.ok(certificateService.adminList(query, volunteerId, activityId, includeDeleted));
    }

    /**
     * <p><b>文件 ↔ 志愿者按文件名里的 11 位手机号匹配</b>（如 {@code 13800138000.pdf}
     * 或 {@code 13800138000_张三.pdf}）。<b>不按姓名匹配——重名必错配</b>。
     * 手机号换 id 走 auth 的窄接口，honor 侧不接触 PII 密文。</p>
     *
     * <p><b>逐条成败独立</b>：单个文件匹配不上只记一行失败，不让整批回滚。</p>
     */
    @Operation(summary = "批量上传 PDF 证书（按文件名手机号匹配志愿者，逐条返回成败）")
    @SaCheckPermission(value = PermissionCode.HONOR_CERTIFICATE, type = "admin")
    @PostMapping("/certificates/batch")
    public Result<CertificateBatchUploadVO> batchUpload(
            @Parameter(description = "本批证书所属活动 id") @RequestParam Long activityId,
            @Parameter(description = "本批证书所属场次 id（一场活动一个证书）") @RequestParam Long slotId,
            @RequestPart("files") List<MultipartFile> files) {
        return Result.ok(certificateService.batchUpload(activityId, slotId, files,
                StpAdminUtil.getLoginIdAsLong()));
    }

    /**
     * <p><b>为什么需要一个人工补发入口</b>：自动补偿扫描只覆盖回看窗口（默认 72 小时）内确认的考勤，
     * 窗口之外是有洞的——「事件丢失 + 应用停机超过窗口」叠加时，那张证书永久缺失且没人会发现。
     * 本接口是该洞唯一的救济。</p>
     *
     * <p>它同时是<b>协会一旦答复「改造前的历史活动要补发证书」时的执行工具</b>：
     * 那条口径落地需要一次显式的、范围由人指定的批量操作，而不是把定时任务的窗口调大。</p>
     *
     * <p><b>范围必须显式给出</b>（时间或活动，至少其一）。注意这道守卫买到的是
     * <b>「防误触」而不是「防规模」</b>：{@code since=1970-01-01T00:00:00} 同样通过、
     * 效果与全量补发一致，这是刻意允许的——人显式敲下 1970 是他的决定，
     * cron 不声不响地把历史证书全发出去不是。真正约束规模的是单次上限。</p>
     *
     * <p><b>单次封顶 + {@code hasMore}</b>：本接口同步执行，而网关
     * {@code proxy_read_timeout} 是 60 秒；一次几千张会在网关 504 而服务端还在跑，
     * 管理员看到失败、拿不到张数，很自然再点一次，两轮重叠白烧一遍。
     * 补发幂等，故「重复点到 {@code hasMore=false}」就是正确用法。</p>
     */
    @Operation(summary = "补发缺失的证书（按确认时间起点/活动指定范围，至少给一个；单次封顶，看 hasMore 决定是否再点）")
    @SaCheckPermission(value = PermissionCode.HONOR_CERTIFICATE, type = "admin")
    @PostMapping("/certificates/reconcile")
    public Result<CertificateReconcileVO> reconcile(
            @Parameter(description = "只补这个时刻之后被秘书部确认的考勤。**须用 ISO 的 T 分隔**，"
                    + "如 2026-07-01T00:00:00——响应里的时间是空格格式，直接复制粘过来会 400")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @Parameter(description = "只补这个活动下的考勤") @RequestParam(required = false) Long activityId) {
        return Result.ok(reconcileJob.reconcile(since, activityId));
    }

    @Operation(summary = "后台下载（同样只返回短期签名 URL）")
    @SaCheckPermission(value = PermissionCode.HONOR_CERTIFICATE, type = "admin")
    @GetMapping("/certificates/{id}/file")
    public Result<String> file(@PathVariable Long id) {
        return Result.ok(certificateService.downloadUrlForAdmin(id));
    }

    @Operation(summary = "软删指定某人证书（记删除人/时间/原因）")
    @SaCheckPermission(value = PermissionCode.HONOR_CERTIFICATE_DELETE, type = "admin")
    @DeleteMapping("/certificates/{id}")
    public Result<Void> delete(@PathVariable Long id, @RequestBody @Valid CertificateDeleteDTO dto) {
        certificateService.softDelete(id, dto.getReason(), StpAdminUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "撤销软删")
    @SaCheckPermission(value = PermissionCode.HONOR_CERTIFICATE_DELETE, type = "admin")
    @PostMapping("/certificates/{id}/restore")
    public Result<Void> restore(@PathVariable Long id) {
        certificateService.restore(id);
        return Result.ok();
    }
}
