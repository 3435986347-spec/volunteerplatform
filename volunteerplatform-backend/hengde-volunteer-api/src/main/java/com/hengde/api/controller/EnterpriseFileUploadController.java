package com.hengde.api.controller;

import com.hengde.api.vo.FileUploadVO;
import com.hengde.common.oss.FileStorageService;
import com.hengde.common.oss.FileValidator;
import com.hengde.common.oss.OssProperties;
import com.hengde.common.oss.PresignedUpload;
import com.hengde.common.result.Result;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.service.SocialMediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 企业端上传（{@code /e/files}，V4 爱心企业批）。要企业登录态（与 {@code /v/files}、{@code /a/files} 的 token 互不通用）；
 * 待审核的企业也能传（审核前要补头像），放行在 {@code EnterpriseAccountGate.UNAPPROVED_ALLOWED}。
 *
 * <p>⚠️ 注册接口本身不收文件、也不开放匿名上传：匿名上传等于给桶开了一个谁都能往里写的口子。头像在注册后登录补上。</p>
 *
 * @author hengde
 */
@Tag(name = "企业端-上传")
@RestController
@RequestMapping("/e/files")
public class EnterpriseFileUploadController {

    /** 与 enterprise 模块 {@code EnterpriseSupport.UPLOAD_DIR} 一致：资料里的头像只收这个目录下的本系统上传 */
    static final String DIR_ENTERPRISE = "enterprise";

    private FileStorageService fileStorageService;
    private OssProperties ossProperties;
    private SocialMediaService socialMediaService;

    @Autowired
    public void setSocialMediaService(SocialMediaService socialMediaService) {
        this.socialMediaService = socialMediaService;
    }

    @Autowired
    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Autowired
    public void setOssProperties(OssProperties ossProperties) {
        this.ossProperties = ossProperties;
    }

    /**
     * 企业发帖的配图：必须落在 {@code social/} 下——发帖时按这个目录核「是不是本系统传的」，
     * 传到 {@code enterprise/} 下的头像拿去发帖会被当成外链拒掉。
     */
    @Operation(summary = "上传社区帖子图片（企业登录态，须审核通过；限图片）")
    @PostMapping("/social-image")
    public Result<FileUploadVO> uploadSocialImage(@RequestParam("file") MultipartFile file) {
        FileValidator.validate(file, FileValidator.IMAGE_EXTENSIONS, ossProperties.getMaxFileSize());
        FileUploadVO vo = new FileUploadVO();
        vo.setUrl(fileStorageService.upload(file, SocialCodes.DIR_IMAGE));
        vo.setName(file.getOriginalFilename());
        vo.setSize(file.getSize());
        return Result.ok(vo);
    }

    @Operation(summary = "社区帖子视频直传签名（企业登录态，须审核通过；extension=mp4|mov，size=字节数）")
    @PostMapping("/social-video/presign")
    public Result<PresignedUpload> presignSocialVideo(@RequestParam("extension") String extension,
                                                      @RequestParam("size") long size) {
        return Result.ok(socialMediaService.presignVideo(extension, size));
    }

    @Operation(summary = "上传企业头像 / 照片（企业登录态，待审核也可；限图片）")
    @PostMapping("/image")
    public Result<FileUploadVO> uploadImage(@RequestParam("file") MultipartFile file) {
        FileValidator.validate(file, FileValidator.IMAGE_EXTENSIONS, ossProperties.getMaxFileSize());
        FileUploadVO vo = new FileUploadVO();
        vo.setUrl(fileStorageService.upload(file, DIR_ENTERPRISE));
        vo.setName(file.getOriginalFilename());
        vo.setSize(file.getSize());
        return Result.ok(vo);
    }
}
