package com.hengde.common.oss;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 对象存储配置，绑定 application.yaml 里的 {@code hengde.oss.*}。
 *
 * <p>白标多实例部署下，每个社会组织一套独立的对象存储凭证与存储桶，因此都来自配置。
 * {@link #enabled} 为 false 时（dev/test 默认）{@link FileStorageService} 不真实上传，
 * 只打日志并返回占位 URL，便于无凭证下跑通本地与测试。</p>
 *
 * <p>{@link #provider} 决定启用哪家实现：{@code aliyun}（默认，{@link AliyunOssFileStorageService}）
 * 或 {@code volc}（火山引擎 TOS，{@code VolcTosFileStorageService}）；两者实现同一接口，业务无感。</p>
 *
 * @author hengde
 */
@Data
@Component
@ConfigurationProperties(prefix = "hengde.oss")
public class OssProperties {

    /** 存储厂商：aliyun（默认）/ volc（火山引擎 TOS）。决定激活哪个 FileStorageService 实现 */
    private String provider = "aliyun";

    /** 是否真实上传。false 时仅打日志返回占位 URL（dev/test 默认） */
    private boolean enabled = false;

    /** 服务接入点：阿里云形如 https://oss-cn-shenzhen.aliyuncs.com；火山 TOS 形如 tos-cn-beijing.volces.com */
    private String endpoint;

    /** 地域，火山引擎 TOS 客户端必填（如 cn-beijing）；阿里云 OSS 用 endpoint 即可，可留空 */
    private String region;

    /** 存储桶名 */
    private String bucket;

    /** 访问凭证 AccessKeyId */
    private String accessKeyId;

    /** 访问凭证 AccessKeySecret */
    private String accessKeySecret;

    /**
     * 访问 URL 前缀（如绑定了 CDN/自定义域名时填写，形如 https://cdn.example.com）。
     * 留空则按「桶名 + endpoint」拼默认外网访问域名。
     */
    private String urlPrefix;

    /** 单文件大小上限（字节），默认 10MB */
    private long maxFileSize = 10 * 1024 * 1024L;

    /** 允许上传的扩展名（小写，不含点），作为 upload(MultipartFile) 的基线校验 */
    private Set<String> allowedExtensions = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp",
            "pdf", "doc", "docx", "xls", "xlsx");
}
