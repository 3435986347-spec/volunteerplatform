package com.hengde.api.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.hengde.activity.constant.PermissionCode;
import com.hengde.api.vo.FileUploadVO;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.oss.FileStorageService;
import com.hengde.common.oss.FileValidator;
import com.hengde.common.oss.OssProperties;
import com.hengde.common.oss.PresignedUpload;
import com.hengde.social.constant.SocialCodes;
import com.hengde.social.service.SocialGateService;
import com.hengde.social.service.SocialMediaService;
import cn.dev33.satoken.stp.StpUtil;
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
 * <p>{@code /upload}（dir=activity，活动封面）需 {@code activity:publish}；{@code /profile-image}（dir=avatar，
 * 个人头像）任意登录志愿者可用（「我的资料」改头像）。两者均限图片、其它目录一律拒绝，避免被借道传公开孤儿文件。
 * 扩展名+大小+<b>文件头魔数</b>校验统一沿用 {@link FileValidator}，写入对象存储的 Content-Type
 * 由服务端按扩展名推导、不采信客户端请求头。</p>
 *
 * @author hengde
 */
@Tag(name = "志愿者端-文件上传")
@RestController
@RequestMapping("/v/files")
public class VolunteerFileUploadController {

    /** 活动封面（需 activity:publish） */
    private static final String DIR_ACTIVITY = "activity";

    /** 个人头像（任意登录志愿者） */
    private static final String DIR_AVATAR = "avatar";

    /** i志愿者码（任意登录志愿者，「我的资料」上传修改） */
    private static final String DIR_IVCODE = "ivcode";

    /** 注册协议手写签名图（任意登录志愿者，注册/资料协议留痕） */
    private static final String DIR_SIGNATURE = "signature";

    /** 申诉凭证图片（任意登录志愿者，奖惩申诉时随理由一起提交） */
    private static final String DIR_APPEAL = "appeal";

    private static final String DIR_GROUP = "group";

    /** 问卷文件题的附件（V4 问卷引擎批）；目录名与 {@code FormSubmissionService.FILE_DIR} 一致——提交时按它校验 URL 是本系统传的 */
    private static final String DIR_FORM = "form";

    private FileStorageService fileStorageService;
    private OssProperties ossProperties;
    private SocialMediaService socialMediaService;
    private SocialGateService socialGateService;

    @Autowired
    public void setSocialMediaService(SocialMediaService socialMediaService) {
        this.socialMediaService = socialMediaService;
    }

    @Autowired
    public void setSocialGateService(SocialGateService socialGateService) {
        this.socialGateService = socialGateService;
    }

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
        return Result.ok(store(file, dir));
    }

    @Operation(summary = "上传个人头像 / i志愿者码 / 协议签名图（任意登录志愿者；仅 dir=avatar|ivcode|signature、限图片）")
    @PostMapping("/profile-image")
    public Result<FileUploadVO> uploadProfileImage(@RequestParam("file") MultipartFile file,
                                                   @RequestParam("dir") String dir) {
        if (DIR_GROUP.equals(dir)) {
            FileValidator.validate(file, FileValidator.IMAGE_EXTENSIONS, ossProperties.getMaxFileSize());
            return Result.ok(store(file, dir));
        }
        // 无 @SaCheckPermission：仅 /v/** 路由级登录校验即可（普通志愿者「我的资料」改头像 / 传 i志愿者码）
        if (!DIR_AVATAR.equals(dir) && !DIR_IVCODE.equals(dir) && !DIR_SIGNATURE.equals(dir)) {
            throw new BusinessException("不支持的上传目录：" + dir);
        }
        FileValidator.validate(file, FileValidator.IMAGE_EXTENSIONS, ossProperties.getMaxFileSize());
        return Result.ok(store(file, dir));
    }

    /**
     * 上传申诉凭证图片。
     *
     * <p><b>为什么单开一个端点、不并进 {@code /profile-image} 的 dir 白名单</b>——
     * 因为它必须能被两道处罚闸门放行，而那两条放行清单是<b>按路径</b>写的：</p>
     *
     * <ul>
     *   <li>{@code DenyAllUseGate} 挡住被判「拒绝使用本程序」的人的整个 {@code /v/**}</li>
     *   <li>{@code BannedAccountGate} 挡住被禁用账号的整个 {@code /v/**}</li>
     * </ul>
     *
     * <p>两者都只放行登录、奖惩记录、申诉、处置查看、站内提示。若把申诉上传挂在
     * {@code /profile-image} 上，要么这两类人传不了凭证——<b>申诉路径就是残的，
     * 而被罚得最重的人恰恰最需要举证</b>；要么为了放行它把整个 {@code /profile-image}
     * 开出去，顺带让被禁用的账号能改头像、换 i志愿者码。<b>单开一条路径两头都不占。</b></p>
     *
     * <p>不挂权限点，理由同上：能提申诉的正是被处罚的人。</p>
     *
     * @param file 图片文件
     * @return 上传结果，{@code url} 随申诉一起提交
     */
    @Operation(summary = "上传申诉凭证图片（任意登录志愿者，含被禁用/被判「拒绝使用」者；限图片）")
    @PostMapping("/appeal-image")
    public Result<FileUploadVO> uploadAppealImage(@RequestParam("file") MultipartFile file) {
        FileValidator.validate(file, FileValidator.IMAGE_EXTENSIONS, ossProperties.getMaxFileSize());
        return Result.ok(store(file, DIR_APPEAL));
    }

    /**
     * 上传问卷文件题的附件（简历、证书扫描件之类）：图片与常见文档（扩展名白名单同后台「文件下载」）。
     *
     * <p>任意登录志愿者；上传得到的 {@code url} 随答卷提交，提交时服务端核对它确实是传到 {@code form/} 下的对象，
     * 客户端自己拼一个外链进答卷是交不上去的。</p>
     */
    @Operation(summary = "上传问卷附件（任意登录志愿者；图片与常见文档）")
    @PostMapping("/form-file")
    public Result<FileUploadVO> uploadFormFile(@RequestParam("file") MultipartFile file) {
        FileValidator.validate(file, ossProperties.getAllowedExtensions(), ossProperties.getMaxFileSize());
        return Result.ok(store(file, DIR_FORM));
    }

    /**
     * 活动相册照片（V4 活动相册批，Row 11「默认上传原图」）：只做图片类型与大小校验，不压缩；传完把 url 交给
     * {@code POST /v/activity/albums/{id}/photos}（那里核对能不能往这个相册传）。已实名才能传。
     */
    @Operation(summary = "上传活动相册照片（已实名志愿者；原图，限图片）")
    @PostMapping("/album-photo")
    public Result<FileUploadVO> uploadAlbumPhoto(@RequestParam("file") MultipartFile file) {
        socialGateService.requireActor(StpUtil.getLoginIdAsLong());
        FileValidator.validate(file, FileValidator.IMAGE_EXTENSIONS, ossProperties.getMaxFileSize());
        return Result.ok(store(file, "album"));
    }

    /** 社区帖子图片（V4 社区核心批）：已实名才能发帖，所以也只有已实名的人能传；限图片。发帖时核对 URL 在 {@code social/} 下。 */
    @Operation(summary = "上传社区帖子图片（已实名志愿者；限图片）")
    @PostMapping("/social-image")
    public Result<FileUploadVO> uploadSocialImage(@RequestParam("file") MultipartFile file) {
        socialGateService.requireActor(StpUtil.getLoginIdAsLong());
        FileValidator.validate(file, FileValidator.IMAGE_EXTENSIONS, ossProperties.getMaxFileSize());
        return Result.ok(store(file, SocialCodes.DIR_IMAGE));
    }

    /**
     * 社区帖子视频直传签名（Q11：不经服务端中转）。拿到的 {@code uploadUrl} 用 {@code method} 带上全部 {@code headers} 直传，
     * 传完把 {@code url} 作为发帖的 {@code videoUrl}。
     */
    @Operation(summary = "社区帖子视频直传签名（已实名志愿者；extension=mp4|mov，size=字节数）")
    @PostMapping("/social-video/presign")
    public Result<PresignedUpload> presignSocialVideo(@RequestParam("extension") String extension,
                                                      @RequestParam("size") long size) {
        socialGateService.requireActor(StpUtil.getLoginIdAsLong());
        return Result.ok(socialMediaService.presignVideo(extension, size));
    }

    private FileUploadVO store(MultipartFile file, String dir) {
        String url = fileStorageService.upload(file, dir);
        FileUploadVO vo = new FileUploadVO();
        vo.setUrl(url);
        vo.setName(file.getOriginalFilename());
        vo.setSize(file.getSize());
        return vo;
    }
}
