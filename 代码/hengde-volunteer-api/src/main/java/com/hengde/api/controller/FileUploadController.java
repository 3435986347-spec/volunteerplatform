package com.hengde.api.controller;

import com.hengde.api.vo.FileUploadVO;
import com.hengde.auth.config.StpAdminUtil;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.oss.FileStorageService;
import com.hengde.common.oss.FileValidator;
import com.hengde.common.oss.OssProperties;
import com.hengde.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * 管理端-通用文件/图片上传。把文件传到对象存储、返回可访问 URL，业务表只存 URL。
 *
 * <p><b>按业务目录 {@code dir} 做细粒度门槛</b>（避免低权限子账号往 OSS 传公开孤儿文件）：
 * 每个 {@code dir} 要求对应「能写该资源」的权限点，且按目录收紧允许的文件类型——图片目录只收图片，
 * {@code file} 目录才收文档；{@code dir} 必传且须在白名单内，未知目录直接拒绝（不静默归类）。
 * 登录态由 {@code /a/**} 路由守卫统一兜底。</p>
 *
 * <table>
 *   <tr><th>dir</th><th>权限点</th><th>允许类型</th><th>用途</th></tr>
 *   <tr><td>banner</td><td>pub:banner</td><td>图片</td><td>轮播图</td></tr>
 *   <tr><td>announcement</td><td>pub:announcement</td><td>图片</td><td>公告封面/插图</td></tr>
 *   <tr><td>activity</td><td>activity:publish 或 edit</td><td>图片</td><td>活动封面</td></tr>
 *   <tr><td>summary</td><td>activity:manage</td><td>图片</td><td>活动总结图</td></tr>
 *   <tr><td>medal</td><td>honor:medal</td><td>图片</td><td>勋章图标</td></tr>
 *   <tr><td>goods</td><td>donate:goods</td><td>图片</td><td>积分商品图片</td></tr>
 *   <tr><td>file</td><td>pub:file</td><td>图片+文档</td><td>文件下载板块</td></tr>
 * </table>
 *
 * <p>文件本身的校验统一由 {@link FileValidator} 负责：扩展名白名单 + 大小 + <b>文件头魔数</b>
 * （内容须与扩展名相符，拦「改后缀绕过」）；写入对象存储的 Content-Type 亦由服务端按扩展名推导，
 * 不采信客户端请求头，避免桶域名下的存储型 XSS。</p>
 *
 * @author hengde
 */
@Tag(name = "管理端-文件上传")
@RestController
@RequestMapping("/a/files")
public class FileUploadController {

    private FileStorageService fileStorageService;
    private OssProperties ossProperties;

    @Autowired
    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Autowired
    public void setOssProperties(OssProperties ossProperties) {
        this.ossProperties = ossProperties;
    }

    @Operation(summary = "通用上传图片/文件，返回可访问 URL（按 dir 做权限+类型门槛，dir 必传）")
    @PostMapping("/upload")
    public Result<FileUploadVO> upload(@RequestParam("file") MultipartFile file,
                                       @RequestParam("dir") String dir) {
        checkDirPermission(dir);
        FileValidator.validate(file, allowedExtensionsFor(dir), ossProperties.getMaxFileSize());
        String url = fileStorageService.upload(file, dir);
        FileUploadVO vo = new FileUploadVO();
        vo.setUrl(url);
        vo.setName(file.getOriginalFilename());
        vo.setSize(file.getSize());
        return Result.ok(vo);
    }

    /** 按目录校验当前管理员是否有「写该资源」的权限；未知目录直接拒绝。超管走 {@code *} 通配放行。 */
    private void checkDirPermission(String dir) {
        switch (dir) {
            case "banner" -> StpAdminUtil.STP_LOGIC.checkPermission("pub:banner");
            case "announcement" -> StpAdminUtil.STP_LOGIC.checkPermission("pub:announcement");
            case "file" -> StpAdminUtil.STP_LOGIC.checkPermission("pub:file");
            case "activity" -> StpAdminUtil.STP_LOGIC.checkPermissionOr("activity:publish", "activity:edit");
            case "summary" -> StpAdminUtil.STP_LOGIC.checkPermission("activity:manage");
            case "medal" -> StpAdminUtil.STP_LOGIC.checkPermission("honor:medal");
            case "goods" -> StpAdminUtil.STP_LOGIC.checkPermission("donate:goods");
            default -> throw new BusinessException("不支持的上传目录：" + dir);
        }
    }

    /** 图片目录只允许图片；{@code file} 目录才允许文档（沿用 OSS 基线扩展名集，含图片+文档）。 */
    private Set<String> allowedExtensionsFor(String dir) {
        return "file".equals(dir) ? ossProperties.getAllowedExtensions() : FileValidator.IMAGE_EXTENSIONS;
    }
}
