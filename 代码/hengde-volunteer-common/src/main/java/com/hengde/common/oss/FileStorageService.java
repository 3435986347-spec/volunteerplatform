package com.hengde.common.oss;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务，封装对象存储的上传/删除。
 *
 * <p>这是 common 提供的「存储层」能力：业务（头像、活动照片、公示图片、文件下载等）
 * 调本接口上传，拿到可访问 URL 存库即可，不关心底层是哪家对象存储。
 * 当前实现为阿里云 OSS（{@link AliyunOssFileStorageService}），后续若换 MinIO 只需另写实现。</p>
 *
 * @author hengde
 */
public interface FileStorageService {

    /**
     * 上传一个上传文件（来自 Controller 的 MultipartFile）。
     *
     * <p>对象名按 {@code dir/yyyyMMdd/UUID.ext} 自动生成，避免重名覆盖。</p>
     *
     * @param file 上传文件
     * @param dir  业务目录（如 avatar、activity、banner）
     * @return 可公开访问的文件 URL
     */
    String upload(MultipartFile file, String dir);

    /**
     * 上传字节数据（用于生成类文件，如二维码、PDF 证书）。
     *
     * @param data        文件字节
     * @param objectName  对象名（含路径，如 cert/2026/abc.pdf）
     * @param contentType MIME 类型，可为 null
     * @return 可公开访问的文件 URL
     */
    String upload(byte[] data, String objectName, String contentType);

    /**
     * 删除对象。
     *
     * @param objectName 对象名（不含域名的存储路径）
     */
    void delete(String objectName);
}
