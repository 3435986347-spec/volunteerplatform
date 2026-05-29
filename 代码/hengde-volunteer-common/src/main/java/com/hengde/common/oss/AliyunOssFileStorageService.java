package com.hengde.common.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.hengde.common.exception.BusinessException;
import com.hengde.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 阿里云 OSS 文件存储实现。
 *
 * <p>设计与 {@link com.hengde.common.sms.SmsServiceImpl} 一致：
 * {@link OssProperties#isEnabled()} 为 false 时不调 SDK，只打日志并返回占位 URL；
 * OSS 客户端按配置懒加载一次后复用；上传失败抛 {@link BusinessException} 交全局处理器兜底。</p>
 *
 * <p>依赖按项目约定用 setter 注入。</p>
 *
 * @author hengde
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "hengde.oss", name = "provider", havingValue = "aliyun", matchIfMissing = true)
public class AliyunOssFileStorageService implements FileStorageService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private OssProperties properties;

    /** OSS 客户端，首次真实上传时懒加载 */
    private volatile OSS ossClient;

    @Autowired
    public void setProperties(OssProperties properties) {
        this.properties = properties;
    }

    @Override
    public String upload(MultipartFile file, String dir) {
        FileValidator.validate(file, properties.getAllowedExtensions(), properties.getMaxFileSize());
        String objectName = buildObjectName(dir, file.getOriginalFilename());
        if (!properties.isEnabled()) {
            log.info("[OSS-MOCK] 未启用真实上传，objectName={} size={}", objectName, file.getSize());
            return "[oss-disabled]/" + objectName;
        }
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(file.getSize());
        if (StringUtils.hasText(file.getContentType())) {
            meta.setContentType(file.getContentType());
        }
        try (InputStream in = file.getInputStream()) {
            client().putObject(properties.getBucket(), objectName, in, meta);
            return url(objectName);
        } catch (Exception e) {
            log.error("[OSS] 上传失败 objectName={}", objectName, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "文件上传失败，请稍后重试");
        }
    }

    @Override
    public String upload(byte[] data, String objectName, String contentType) {
        if (!properties.isEnabled()) {
            log.info("[OSS-MOCK] 未启用真实上传，objectName={} size={}", objectName, data.length);
            return "[oss-disabled]/" + objectName;
        }
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(data.length);
        if (StringUtils.hasText(contentType)) {
            meta.setContentType(contentType);
        }
        try {
            client().putObject(properties.getBucket(), objectName, new ByteArrayInputStream(data), meta);
            return url(objectName);
        } catch (Exception e) {
            log.error("[OSS] 上传失败 objectName={}", objectName, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "文件上传失败，请稍后重试");
        }
    }

    @Override
    public void delete(String objectName) {
        if (!properties.isEnabled()) {
            log.info("[OSS-MOCK] 未启用真实删除，objectName={}", objectName);
            return;
        }
        try {
            client().deleteObject(properties.getBucket(), objectName);
        } catch (Exception e) {
            log.error("[OSS] 删除失败 objectName={}", objectName, e);
            throw new BusinessException(ResultCode.SERVER_ERROR.getCode(), "文件删除失败");
        }
    }

    /** 生成对象名：{@code dir/yyyyMMdd/UUID.ext}，避免重名覆盖 */
    private String buildObjectName(String dir, String originalFilename) {
        String ext = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf('.'));
        }
        String prefix = StringUtils.hasText(dir) ? trimSlash(dir) + "/" : "";
        return prefix + LocalDate.now().format(DATE_FMT) + "/"
                + UUID.randomUUID().toString().replace("-", "") + ext;
    }

    /** 拼接可公开访问 URL：优先用配置的 urlPrefix，否则按「桶名 + endpoint」拼默认外网域名 */
    private String url(String objectName) {
        if (StringUtils.hasText(properties.getUrlPrefix())) {
            return trimSlash(properties.getUrlPrefix()) + "/" + objectName;
        }
        String host = properties.getEndpoint().replaceFirst("^https?://", "");
        return "https://" + properties.getBucket() + "." + host + "/" + objectName;
    }

    private String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** 懒加载 OSS 客户端：双重检查，保证只构建一次并复用 */
    private OSS client() {
        if (ossClient == null) {
            synchronized (this) {
                if (ossClient == null) {
                    ossClient = new OSSClientBuilder().build(
                            properties.getEndpoint(),
                            properties.getAccessKeyId(),
                            properties.getAccessKeySecret());
                }
            }
        }
        return ossClient;
    }
}
