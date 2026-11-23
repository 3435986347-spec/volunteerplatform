package com.hengde.social.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.oss.FileStorageService;
import com.hengde.common.oss.PresignedUpload;
import com.hengde.social.config.SocialProperties;
import com.hengde.social.constant.SocialCodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 帖子的图片 / 视频：校验、序列化、视频直传签名。
 *
 * <p><b>只收本系统传的</b>（图片在 {@code social/}、视频在 {@code social-video/}）——不校验的话帖子里能挂任意外链，
 * 别人点开的就是别人的页面（与问卷文件题、投诉图片同一判定）。</p>
 *
 * <p><b>视频不走服务端中转</b>（Q11 默认 100MB）：服务端按扩展名与声明的大小签一条限时 PUT，小程序直传对象存储，
 * 发帖时再交回对象 URL。⚠️ 与支付、快递100 一样，<b>真实对象存储上的直传没有端到端验证过</b>——用例只跑到存储未启用时的占位 URL。</p>
 *
 * @author hengde
 */
@Service
public class SocialMediaService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FileStorageService fileStorageService;
    private SocialProperties properties;

    @Autowired
    public void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Autowired
    public void setProperties(SocialProperties properties) {
        this.properties = properties;
    }

    /** 校验过的媒体：类型 + URL 列表。 */
    public record Media(int type, List<String> urls) {
    }

    public Media validate(String content, List<String> imageUrls, String videoUrl) {
        List<String> images = imageUrls == null ? List.of()
                : imageUrls.stream().filter(Objects::nonNull).map(String::trim).filter(StringUtils::hasText).toList();
        boolean hasVideo = StringUtils.hasText(videoUrl);
        if (!images.isEmpty() && hasVideo) {
            throw new BusinessException("图片和视频只能二选一");
        }
        if (images.size() > SocialCodes.MAX_IMAGES) {
            throw new BusinessException("图片最多 " + SocialCodes.MAX_IMAGES + " 张");
        }
        if (images.size() != images.stream().distinct().count()) {
            throw new BusinessException("同一张图片不要重复添加");
        }
        for (String url : images) {
            if (!fileStorageService.isOwnUpload(url, SocialCodes.DIR_IMAGE)) {
                throw new BusinessException("图片请先通过小程序上传");
            }
        }
        if (hasVideo && !fileStorageService.isOwnUpload(videoUrl.trim(), SocialCodes.DIR_VIDEO)) {
            throw new BusinessException("视频请先通过小程序上传");
        }
        if (!StringUtils.hasText(content) && images.isEmpty() && !hasVideo) {
            throw new BusinessException("写点什么，或者配一张图片 / 一段视频");
        }
        if (content != null && content.trim().length() > SocialCodes.MAX_CONTENT) {
            throw new BusinessException("正文不超过 " + SocialCodes.MAX_CONTENT + " 字");
        }
        if (hasVideo) {
            return new Media(SocialCodes.MEDIA_VIDEO, List.of(videoUrl.trim()));
        }
        return new Media(images.isEmpty() ? SocialCodes.MEDIA_NONE : SocialCodes.MEDIA_IMAGE, images);
    }

    /** 相册同步发帖的照片：只收本系统传到 album/ 下的，去重，最多取前 9 张（一条帖子放不下更多）。 */
    public List<String> validateAlbumPhotos(List<String> photoUrls) {
        List<String> urls = photoUrls == null ? List.of()
                : photoUrls.stream().filter(Objects::nonNull).map(String::trim).filter(StringUtils::hasText).distinct()
                .limit(SocialCodes.MAX_IMAGES).toList();
        for (String url : urls) {
            if (!fileStorageService.isOwnUpload(url, "album")) {
                throw new BusinessException("相册照片不是本系统上传的");
            }
        }
        return urls;
    }

    public String toJson(Media media) {
        if (media.urls().isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(media.urls());
        } catch (Exception e) {
            throw new IllegalStateException("媒体序列化失败", e);
        }
    }

    public List<String> fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 帖子视频直传签名（Q11）：扩展名在白名单内、声明的大小在上限内才签；签出的 PUT 带着 Content-Type 与 Content-Length，
     * 传的不是这个大小对象存储会拒收（火山 TOS 把请求头纳入签名；阿里云 V1 签名不含 Content-Length，那一侧只能靠前端约束）。
     */
    public PresignedUpload presignVideo(String extension, long size) {
        String ext = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        if (!properties.getVideoExtensions().contains(ext)) {
            throw new BusinessException("视频只支持 " + String.join(" / ", properties.getVideoExtensions()) + " 格式");
        }
        if (size <= 0) {
            throw new BusinessException("请告诉我视频有多大");
        }
        if (size > properties.getVideoMaxBytes()) {
            throw new BusinessException("视频不超过 " + (properties.getVideoMaxBytes() / 1024 / 1024) + "MB");
        }
        return fileStorageService.presignPut(SocialCodes.DIR_VIDEO, ext, size,
                Duration.ofSeconds(properties.getVideoPresignTtlSeconds()));
    }
}
