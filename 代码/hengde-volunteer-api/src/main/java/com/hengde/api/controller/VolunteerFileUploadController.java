package com.hengde.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.activity.constant.PermissionCode;
import com.hengde.api.vo.FileUploadVO;
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

/**
 * 志愿者端-图片上传（给「管理团队」志愿者在小程序发布活动时传封面用）。
 *
 * <p>与管理端 {@link FileUploadController}（{@code /a/files}，{@code StpAdminUtil}）分开：小程序持志愿者
 * token，过不了 {@code /a/**} 的管理端登录校验，故单列 {@code /v} 入口。权限走默认 {@code login} 域、
 * 吃 organization 的志愿者 RBAC——与发布活动同一权限点 {@link PermissionCode#ACTIVITY_PUBLISH}，
 * 普通/游客志愿者无此码即被拒。</p>
 *
 * <p>仅放开 {@code dir=activity}（活动封面，限图片）；其它目录一律拒绝，避免被借道传公开孤儿文件。
 * 扩展名+大小校验沿用 {@link FileValidator}；魔数校验同管理端一并延后（仅对有发布权的志愿者开放，风险可控）。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-文件上传")
@RestController
@RequestMapping("/v/files")
public class VolunteerFileUploadController {

    /** 志愿者端唯一放开的上传目录：活动封面 */
    private static final String DIR_ACTIVITY = "activity";

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

    @Operation(summary = "上传活动封面（管理团队志愿者，需 activity:publish；仅 dir=activity、限图片）")
    @SaCheckPermission(PermissionCode.ACTIVITY_PUBLISH)
    @PostMapping("/upload")
    public Result<FileUploadVO> upload(@RequestParam("file") MultipartFile file,
                                       @RequestParam("dir") String dir) {
        if (!DIR_ACTIVITY.equals(dir)) {
            throw new BusinessException("不支持的上传目录：" + dir);
        }
        FileValidator.validate(file, FileValidator.IMAGE_EXTENSIONS, ossProperties.getMaxFileSize());
        String url = fileStorageService.upload(file, dir);
        FileUploadVO vo = new FileUploadVO();
        vo.setUrl(url);
        vo.setName(file.getOriginalFilename());
        vo.setSize(file.getSize());
        return Result.ok(vo);
    }
}
