package com.hengde.common.oss;

import com.hengde.common.exception.BusinessException;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * 上传文件校验：扩展名 + 大小。
 *
 * <p>{@link AliyunOssFileStorageService#upload} 入口已用 {@link OssProperties} 配置做基线校验；
 * 调用方若要按场景收紧（如头像仅允许图片），可在上传前自行调
 * {@code FileValidator.validate(file, FileValidator.IMAGE_EXTENSIONS, maxSize)}。</p>
 *
 * @author hengde
 */
public final class FileValidator {

    /** 图片类扩展名，供「仅允许图片」的场景（头像、轮播图、签名等）使用 */
    public static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    private FileValidator() {
    }

    /**
     * 校验上传文件，不合规抛 {@link BusinessException}（400）。
     *
     * @param file              上传文件
     * @param allowedExtensions 允许的扩展名集合（小写，不含点）
     * @param maxSize           大小上限（字节）
     */
    public static void validate(MultipartFile file, Set<String> allowedExtensions, long maxSize) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > maxSize) {
            throw new BusinessException("文件大小超过限制（最大 " + (maxSize / 1024 / 1024) + "MB）");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!StringUtils.hasText(ext) || !allowedExtensions.contains(ext)) {
            throw new BusinessException("不支持的文件类型：" + (StringUtils.hasText(ext) ? ext : "未知"));
        }
    }

    /**
     * 取文件扩展名（小写、不含点）。
     *
     * @param filename 文件名
     * @return 扩展名；无扩展名返回空串
     */
    public static String extensionOf(String filename) {
        if (!StringUtils.hasText(filename) || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
